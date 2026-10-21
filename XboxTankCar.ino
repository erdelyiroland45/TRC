// Original ESP32-WROOM / ESP32 Dev Module, Arduino-ESP32 3.x.
// Bluetooth Classic SPP server. No Wi-Fi, BLE or Xbox dependency.
#include <Arduino.h>
#include <BluetoothSerial.h>
#include <esp_system.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include "ControlProtocol.h"
#include "FirmwareSafetyTests.h"
#if !defined(CONFIG_BT_SPP_ENABLED)
#error Bluetooth Classic SPP requires an original ESP32 (not C3/S3).
#endif
constexpr int ENA=25, IN1=26, IN2=27, ENB=33, IN3=18, IN4=19;
constexpr int FRONT_LIGHTS=23, REAR_LIGHTS=22;
BluetoothSerial bluetooth;
Car::Session control;
Car::Parser parser;
portMUX_TYPE linkMux=portMUX_INITIALIZER_UNLOCKED;
uint32_t linkHandle=0, linkEpoch=0;
uint8_t rx[256], reply[Car::FRAME_SIZE];
size_t readAt=0, writeAt=0, count=0;
bool overflow=false, replyBusy=false, restartBluetooth=false;
enum LogKind : uint8_t { PHONE_CONNECTED, PHONE_DISCONNECTED, PHONE_REJECTED,
                         PAIR_OK, PAIR_FAILED, HANDSHAKE_OK, CONTROL_ARMED, DRIVE_APPLIED };
struct LogEvent {
  uint8_t kind;
  uint32_t at,handle;
  uint8_t address[6];
  uint8_t command,speed,directions;
  int16_t left,right;
  uint16_t dutyLeft,dutyRight;
};
QueueHandle_t logQueue=nullptr;
void queueLog(uint8_t kind,uint32_t handle,const uint8_t *address) {
  if(!logQueue) return;
  LogEvent event={kind,millis(),handle,{}};
  if(address) memcpy(event.address,address,sizeof(event.address));
  xQueueSend(logQueue,&event,0); // Never wait inside a Bluetooth callback.
}
void queueLog(uint8_t kind,uint32_t handle) { queueLog(kind,handle,nullptr); }
void queueDriveLog(uint8_t command,uint8_t speed,int left,int right) {
  if(!logQueue) return;
  LogEvent event={};
  event.kind=DRIVE_APPLIED; event.at=millis(); event.command=command; event.speed=speed;
  event.left=left; event.right=right;
  event.dutyLeft=ledcRead(ENA); event.dutyRight=ledcRead(ENB);
  event.directions=digitalRead(IN1)|(digitalRead(IN2)<<1)|(digitalRead(IN3)<<2)|(digitalRead(IN4)<<3);
  xQueueSend(logQueue,&event,0);
}
void flushLogs() {
  LogEvent event;
  if(!logQueue || xQueuePeek(logQueue,&event,0)!=pdTRUE) return;
  char line[180]; int n=0;
  switch(event.kind) {
    case PHONE_CONNECTED:
    case PHONE_REJECTED:
      n=snprintf(line,sizeof(line),"[%lu ms] %s (SPP #%lu, %02X:%02X:%02X:%02X:%02X:%02X).\n",
          (unsigned long)event.at,event.kind==PHONE_CONNECTED?"Telefon csatlakozott":"Masodik telefon elutasitva",
          (unsigned long)event.handle,event.address[0],event.address[1],event.address[2],
          event.address[3],event.address[4],event.address[5]);
      break;
    case PHONE_DISCONNECTED:
      n=snprintf(line,sizeof(line),"[%lu ms] Telefon lecsatlakozott (SPP #%lu). Motorok leallitva; uj telefonra varok.\n",
          (unsigned long)event.at,(unsigned long)event.handle);
      break;
    case DRIVE_APPLIED: {
      const char *names[]={"STOP","ELORE","HATRA","BALRA","JOBBRA"};
      n=snprintf(line,sizeof(line),
          "[%lu ms] Parancs=%s fokozat=%u bal=%d jobb=%d PWM25=%u PWM33=%u IN1..4=%u%u%u%u\n",
          (unsigned long)event.at,names[event.command],event.speed,event.left,event.right,
          event.dutyLeft,event.dutyRight,event.directions&1,(event.directions>>1)&1,
          (event.directions>>2)&1,(event.directions>>3)&1);
      break;
    }
    default: {
      const char *message=event.kind==PAIR_OK?"Bluetooth parositas sikeres.":
          event.kind==PAIR_FAILED?"Bluetooth parositas sikertelen.":
          event.kind==HANDSHAKE_OK?"App kezfogas sikeres; STOP csomagra varok.":
          "Vezerles elesitve; uj iranygombnyomasra indulhat.";
      n=snprintf(line,sizeof(line),"[%lu ms] %s\n",(unsigned long)event.at,message);
      break;
    }
  }
  // Motor/watchdog processing must never wait for a full serial TX buffer.
  if(n<=0 || n>=(int)sizeof(line) || Serial.availableForWrite()<n) return;
  xQueueReceive(logQueue,&event,0);
  Serial.write((const uint8_t*)line,n);
}

void motor(int en,int a,int b,int speed) {
  ledcWrite(en,0); digitalWrite(a,speed>0); digitalWrite(b,speed<0);
  ledcWrite(en,abs(speed));
}
void stopCar() {
  motor(ENA,IN1,IN2,0); motor(ENB,IN3,IN4,0);
  digitalWrite(REAR_LIGHTS,LOW);
}
// BT callbacks only manage transport state; motors are driven in loop().
void bluetoothEvent(esp_spp_cb_event_t event,esp_spp_cb_param_t *p) {
  bool connected=false,disconnected=false,rejected=false;
  portENTER_CRITICAL(&linkMux);
  if(event==ESP_SPP_SRV_OPEN_EVT && p->srv_open.status==ESP_SPP_SUCCESS && !linkHandle) {
    linkHandle=p->srv_open.handle; ++linkEpoch;
    readAt=writeAt=count=0; overflow=replyBusy=false;
    connected=true;
  } else if(event==ESP_SPP_SRV_OPEN_EVT && p->srv_open.status==ESP_SPP_SUCCESS) {
    rejected=true;
  } else if(event==ESP_SPP_CLOSE_EVT && p->close.handle==linkHandle) {
    // Core 3.x can retain a stale client when two close events race.
    // Recover only after the actual owner closed; a rejected second phone cannot trigger this.
    restartBluetooth=bluetooth.hasClient();
    linkHandle=0; ++linkEpoch; readAt=writeAt=count=0;
    disconnected=true;
  } else if(event==ESP_SPP_DATA_IND_EVT && p->data_ind.handle==linkHandle) {
    for(size_t i=0;i<p->data_ind.len;i++) {
      if(count==sizeof(rx)) { overflow=true; break; }
      rx[writeAt]=p->data_ind.data[i]; writeAt=(writeAt+1)%sizeof(rx); ++count;
    }
  } else if(event==ESP_SPP_WRITE_EVT && p->write.handle==linkHandle) {
    replyBusy=false;
  }
  portEXIT_CRITICAL(&linkMux);
  if(connected) queueLog(PHONE_CONNECTED,p->srv_open.handle,p->srv_open.rem_bda);
  if(rejected) queueLog(PHONE_REJECTED,p->srv_open.handle,p->srv_open.rem_bda);
  if(disconnected) queueLog(PHONE_DISCONNECTED,p->close.handle);
}
void dropLink(uint32_t handle,const char *reason) {
  stopCar(); // Always remove motor power before touching the Bluetooth stack.
  control.armed=false;
  Serial.printf("[%lu ms] Biztonsagi leallitas: %s. Bluetooth bontasa...\n",(unsigned long)millis(),reason);
  esp_spp_disconnect(handle); // Nonblocking, unlike BluetoothSerial::disconnect().
}
void setup() {
  Serial.setTxBufferSize(1024);
  Serial.begin(115200);
  logQueue=xQueueCreate(12,sizeof(LogEvent));
  const int pins[]={ENA,IN1,IN2,ENB,IN3,IN4,FRONT_LIGHTS,REAR_LIGHTS};
  for(int pin:pins) { pinMode(pin,OUTPUT); digitalWrite(pin,LOW); }
  bool a=ledcAttach(ENA,1000,8), b=ledcAttach(ENB,1000,8);
  if(!a || !b) {
    Serial.println("PWM inicializalas sikertelen. Motorok letiltva.");
    while(true) delay(1000);
  }
  stopCar();
  bluetooth.enableSSP(false,false);
  // Use our bounded RX buffer with handle checks, not the library's shared RX queue.
  bluetooth.onData([](const uint8_t*,size_t){});
  bluetooth.register_callback(bluetoothEvent);
  bluetooth.onAuthComplete([](boolean success){ queueLog(success?PAIR_OK:PAIR_FAILED,0); });
  if(!bluetooth.begin("TankCar",false,true)) {
    Serial.println("Bluetooth inicializalas sikertelen. Motorok letiltva."); while(true) delay(1000);
  }
  Serial.println("TankCar Bluetooth kesz. Telefon kapcsolodasara varok...");
  Serial.println("Parositas: rendszer Bluetooth-beallitasok. Az appban: Kapcsolodas.");
  if(!logQueue) Serial.println("Figyelem: az esemenynaplo puffere nem hozhato letre.");
}
void loop() {
  static uint32_t seenEpoch=0;
  static bool dropping=false;
  static int lastCommand=-1,lastSpeed=-1;
  uint32_t handle,epoch; bool full,restart;
  portENTER_CRITICAL(&linkMux);
  handle=linkHandle; epoch=linkEpoch; full=overflow; restart=restartBluetooth;
  portEXIT_CRITICAL(&linkMux);
  if(restart) {
    stopCar(); flushLogs();
    Serial.println("Bluetooth allapothiba bontas utan. Biztonsagos ujrainditas...");
    Serial.flush(); delay(20); ESP.restart(); return;
  }
  if(epoch!=seenEpoch) {
    stopCar(); parser.reset(); dropping=false; seenEpoch=epoch;
    lastCommand=lastSpeed=-1;
    uint32_t token; do { token=esp_random(); } while(!token);
    control.reset(token,millis());
  }
  if(!handle) { stopCar(); flushLogs(); delay(1); return; }
  if(dropping) { stopCar(); flushLogs(); delay(1); return; }
  if(full || control.expired(millis())) {
    dropping=true;
    dropLink(handle,full?"fogado puffer tulcsordult":
        control.claimed?"500 ms ota nincs ervenyes vezerlojel":"az app nem kuldott kezfogast 3 masodpercig");
    return;
  }
  flushLogs();
  for(int budget=0;budget<128;budget++) {
    int byte=-1;
    portENTER_CRITICAL(&linkMux);
    bool same=linkEpoch==epoch && linkHandle==handle;
    if(same && count) { byte=rx[readAt]; readAt=(readAt+1)%sizeof(rx); --count; }
    portEXIT_CRITICAL(&linkMux);
    if(!same) { stopCar(); return; }
    if(byte<0) break;
    if(!parser.push(uint8_t(byte))) continue;
    bool wasClaimed=control.claimed, wasArmed=control.armed;
    if(!control.accept(parser.data,millis())) continue;
    if(!wasClaimed) queueLog(HANDSHAKE_OK,handle);
    if(!wasArmed && control.armed) queueLog(CONTROL_ARMED,handle);
    motor(ENA,IN1,IN2,control.output.left); motor(ENB,IN3,IN4,control.output.right);
    digitalWrite(REAR_LIGHTS,control.output.rear); digitalWrite(FRONT_LIGHTS,control.lights);
    if(control.armed && (parser.data[13]!=lastCommand || parser.data[12]!=lastSpeed)) {
      queueDriveLog(parser.data[13],parser.data[12],control.output.left,control.output.right);
      lastCommand=parser.data[13]; lastSpeed=parser.data[12];
    }
    bool send=false;
    portENTER_CRITICAL(&linkMux);
    if(linkEpoch==epoch && !replyBusy) { replyBusy=true; send=true; }
    portEXIT_CRITICAL(&linkMux);
    if(send) {
      memcpy(reply,parser.data,sizeof(reply)); Car::put32(reply+4,control.token);
      reply[15]=control.armed?1:0; Car::seal(reply);
      if(esp_spp_write(handle,sizeof(reply),reply)!=ESP_OK) {
        dropping=true; dropLink(handle,"Bluetooth valasz kuldese sikertelen"); return;
      }
    }
  }
  delay(1);
}
