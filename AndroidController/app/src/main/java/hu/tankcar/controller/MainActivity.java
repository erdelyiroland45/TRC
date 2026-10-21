package hu.tankcar.controller;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Plain Java + Android framework. Connects only to the configured car address. */
@SuppressLint({"MissingPermission", "SetTextI18n"})
public class MainActivity extends Activity {
    private static final UUID SPP=UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final int[] SPEED_VALUES={3,6,10};
    private static final String[] SPEED_NAMES={"Lassú","Közepes","Gyors"};
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ArrayList<Button> directions=new ArrayList<>();
    private TextView status,speedLabel,driveStatus;
    private BluetoothAdapter adapter;
    private Client client;
    private int held,speedIndex;
    private SpeedLabels speedLabels;
    private boolean lights,ready,resumed;
    private boolean autoConnect=true,permissionAsked;
    private static final long RETRY_MS=1500;
    private final Runnable retry=this::tryAutoConnect;
    private volatile Input input=new Input(SPEED_VALUES[0],Protocol.STOP,false);
    private volatile long uiTick;
    private static final class Input {
        final int speed,command; final boolean lights;
        Input(int s,int c,boolean l) { speed=s; command=c; lights=l; }
    }
    private final Runnable pulse=new Runnable() {
        @Override public void run() { uiTick=SystemClock.elapsedRealtime(); if(resumed) main.postDelayed(this,100); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter=manager==null?null:manager.getAdapter();
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff12171e);
        root.setPadding(dp(12),0,dp(12),0);
        root.setOnApplyWindowInsetsListener((v,i)->{
            v.setPadding(dp(12)+i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),
                dp(12)+i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom()); return i;
        });
        LinearLayout bar=new LinearLayout(this);
        status=button("Kapcsolódás"); status.setTextSize(14); status.setGravity(Gravity.CENTER);
        status.setContentDescription("Automatikus kapcsolódás indítása az autóhoz");
        status.setOnClickListener(v->startAutoConnect());
        bar.addView(status,new LinearLayout.LayoutParams(0,-1,1));
        Button bluetooth=button("Bluetooth"); bluetooth.setOnClickListener(v->openBluetoothSettings()); bar.addView(bluetooth);
        Button light=button("Lámpa"); light.setOnClickListener(v->{ lights=!lights; publish(); }); bar.addView(light);
        Button unlink=button("Szétkapcsolás"); unlink.setOnClickListener(v->disconnectManually()); bar.addView(unlink);
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(54)));
        LinearLayout body=new LinearLayout(this);
        body.addView(new DPad(),new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout speedPanel=new LinearLayout(this); speedPanel.setOrientation(LinearLayout.VERTICAL);
        speedPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        speedLabel=new TextView(this); speedLabel.setTextSize(18); speedLabel.setGravity(Gravity.CENTER);
        speedLabel.setTextColor(0xff79cbc4); speedLabel.setTypeface(null,Typeface.BOLD);
        speedPanel.addView(speedLabel,new LinearLayout.LayoutParams(-1,dp(36)));
        LinearLayout selector=new LinearLayout(this);
        VerticalSeekBar seek=new VerticalSeekBar(); seek.setMax(2); seek.setProgress(0);
        seek.setContentDescription("Sebesség: Lassú, Közepes, Gyors");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s,int value,boolean user) { speedIndex=value; publish(); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        selector.addView(seek,new LinearLayout.LayoutParams(dp(52),-1));
        speedLabels=new SpeedLabels(); selector.addView(speedLabels,new LinearLayout.LayoutParams(0,-1,1));
        speedPanel.addView(selector,new LinearLayout.LayoutParams(-1,0,1));
        body.addView(speedPanel,new LinearLayout.LayoutParams(dp(152),-1));
        root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        driveStatus=label("Motorvezérlés tiltva – nincs élesített kapcsolat");
        driveStatus.setTextSize(12); root.addView(driveStatus); setContentView(root);
        setReady(false,"Kapcsolódás"); publish();
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private Button button(String text) { Button b=new Button(this); b.setText(text); return b; }
    private TextView label(String text) { TextView t=new TextView(this); t.setText(text); t.setGravity(Gravity.CENTER); return t; }
    @SuppressLint("ClickableViewAccessibility")
    private Button direction(String text,int command) {
        Button b=button(text); b.setTextSize(16); directions.add(b);
        b.setContentDescription(text.replace('\n',' ')); stylePadButton(b,false);
        int bit=1<<(command-1);
        b.setOnTouchListener((v,e)->{
            int action=e.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN) {
                if(ready) { held|=bit; v.setPressed(true); }
            } else if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL ||
                (action==MotionEvent.ACTION_MOVE && (e.getX()<0 || e.getY()<0 || e.getX()>v.getWidth() || e.getY()>v.getHeight()))) {
                held&=~bit; v.setPressed(false);
            }
            publish(); return true;
        });
        return b;
    }
    private void publish() {
        input=new Input(SPEED_VALUES[speedIndex],ready?Protocol.command(held):Protocol.STOP,lights);
        if(speedLabel!=null) speedLabel.setText(SPEED_NAMES[speedIndex]);
        if(speedLabels!=null) speedLabels.invalidate();
    }
    private void stylePadButton(Button button,boolean stop) {
        button.setAllCaps(false); button.setTextColor(Color.WHITE);
        button.setTypeface(null,Typeface.BOLD); button.setPadding(dp(4),0,dp(4),0);
        button.setMinWidth(0); button.setMinHeight(0);
        button.setMinimumWidth(0); button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        StateListDrawable background=new StateListDrawable();
        for(boolean pressed:new boolean[]{true,false}) {
            GradientDrawable shape=new GradientDrawable();
            shape.setColor(stop?(pressed?0xffcf5260:0xff6c303c):(pressed?0xff278e87:0xff29394b));
            shape.setCornerRadius(stop?dp(100):dp(12));
            shape.setStroke(dp(1),stop?0xffe27682:0xff3c536c);
            background.addState(pressed?new int[]{android.R.attr.state_pressed}:new int[]{},shape);
        }
        button.setBackground(background);
    }
    /** Entire square cross is aligned to the left edge; corners stay empty. */
    private final class DPad extends ViewGroup {
        private final int[] columns={1,0,1,2,1}, rows={0,1,1,1,2};
        private int cell,side;
        DPad() {
            super(MainActivity.this); setMotionEventSplittingEnabled(true);
            addView(direction("▲\nELŐRE",Protocol.FORWARD));
            addView(direction("◀\nBALRA",Protocol.LEFT));
            Button stop=button("STOP"); stylePadButton(stop,true); stop.setTextSize(13);
            stop.setOnClickListener(v->clearDirections()); addView(stop);
            addView(direction("▶\nJOBBRA",Protocol.RIGHT));
            addView(direction("▼\nHÁTRA",Protocol.REVERSE));
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec) {
            int w=resolveSize(dp(320),widthSpec), h=resolveSize(dp(320),heightSpec);
            cell=Math.max(1,(Math.min(Math.min(w,h),dp(360))-dp(28))/3);
            side=cell*3+dp(12);
            for(int i=0;i<getChildCount();i++) {
                int size=i==2?Math.round(cell*.72f):cell;
                int spec=MeasureSpec.makeMeasureSpec(size,MeasureSpec.EXACTLY);
                getChildAt(i).measure(spec,spec);
            }
            setMeasuredDimension(w,h);
        }
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
            int left=0, top=(getHeight()-side)/2;
            for(int i=0;i<getChildCount();i++) {
                View child=getChildAt(i); int size=child.getMeasuredWidth();
                int x=left+columns[i]*(cell+dp(6))+(cell-size)/2;
                int y=top+rows[i]*(cell+dp(6))+(cell-size)/2;
                child.layout(x,y,x+size,y+size);
            }
        }
    }
    private final class SpeedLabels extends View {
        private final Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);
        SpeedLabels() {
            super(MainActivity.this); text.setTextSize(14*getResources().getDisplayMetrics().scaledDensity);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Paint.FontMetrics font=text.getFontMetrics();
            float length=getHeight()-dp(24);
            for(int step=0;step<3;step++) {
                text.setColor(step==speedIndex?0xff79cbc4:0xff9aabbc);
                float y=getHeight()-dp(12)-length*step/2f;
                canvas.drawText(SPEED_NAMES[step],0,y-(font.ascent+font.descent)/2,text);
            }
        }
    }
    private void clearDirections() {
        held=0; for(Button b:directions) b.setPressed(false); publish();
    }
    private void setReady(boolean value,String message) {
        if(!value || !ready) clearDirections();
        ready=value; status.setText(message); publish();
        if(!value && driveStatus!=null) driveStatus.setText("Motorvezérlés tiltva – várakozás az ESP-re");
    }
    @Override protected void onResume() {
        super.onResume(); resumed=true; main.post(pulse);
        tryAutoConnect();
    }
    @Override protected void onPause() {
        resumed=false; main.removeCallbacks(pulse); main.removeCallbacks(retry);
        closeConnection(); super.onPause();
    }
    private void closeConnection() {
        Client old=client; client=null; if(old!=null) old.close();
        setReady(false,"Kapcsolódás");
    }
    private void disconnectManually() {
        autoConnect=false; main.removeCallbacks(retry); closeConnection();
        status.setText("Szétkapcsolva · Kapcsolódás");
    }
    private void openBluetoothSettings() {
        main.removeCallbacks(retry); closeConnection();
        startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    }
    private void startAutoConnect() {
        autoConnect=true; permissionAsked=false;
        main.removeCallbacks(retry); tryAutoConnect();
    }
    private void scheduleRetry() {
        main.removeCallbacks(retry);
        if(resumed && autoConnect) main.postDelayed(retry,RETRY_MS);
    }
    private void tryAutoConnect() {
        if(!resumed || !autoConnect || client!=null) return;
        clearDirections();
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
            status.setText("Bluetooth-engedély szükséges");
            if(!permissionAsked) {
                permissionAsked=true;
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},1);
            }
            return;
        }
        if(adapter==null) { status.setText("Ezen az eszközön nincs Bluetooth"); return; }
        if(!adapter.isEnabled()) {
            status.setText("Kapcsold be a Bluetooth-t"); scheduleRetry(); return;
        }
        if(!BluetoothAdapter.checkBluetoothAddress(CarConfig.CAR_MAC_ADDRESS)) {
            status.setText("Érvénytelen MAC-cím a CarConfig fájlban"); return;
        }
        BluetoothDevice device=adapter.getRemoteDevice(CarConfig.CAR_MAC_ADDRESS);
        int bond=device.getBondState();
        if(bond==BluetoothDevice.BOND_BONDING) {
            status.setText("Párosítás folyamatban…"); scheduleRetry(); return;
        }
        connect(device);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==1 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) tryAutoConnect();
        else if(code==1) status.setText("A Bluetooth használatához engedély szükséges");
    }
    private void connect(BluetoothDevice device) {
        main.removeCallbacks(retry); closeConnection();
        Client next=new Client(device); client=next;
        setReady(false,"Kapcsolódás…");
        main.postDelayed(next.connectTimeout,12000);
        new Thread(next,"TankCar Bluetooth").start();
    }
    private void report(Client source,boolean active,String message,int command,int speed) {
        main.post(()->{
            if(client!=source || !source.running || !resumed) return;
            setReady(active,message);
            if(active) {
                String[] names={"STOP","ELŐRE","HÁTRA","BALRA","JOBBRA"};
                String name=command>=0 && command<names.length?names[command]:"ISMERETLEN";
                int pwm=command==Protocol.STOP?0:(speed*255+5)/10;
                driveStatus.setText("ESP visszaigazolta: "+name+" · PWM "+pwm+"/255");
            }
        });
    }
    private final class Client implements Runnable {
        final BluetoothDevice device;
        volatile boolean running=true,connected;
        volatile BluetoothSocket socket;
        final Runnable connectTimeout=()->{
            if(client==this && !connected) {
                close(); client=null; setReady(false,"Időtúllépés · Újrapróbálkozás…"); scheduleRetry();
            }
        };
        Client(BluetoothDevice d) { device=d; }
        void close() {
            running=false; main.removeCallbacks(connectTimeout);
            try { if(socket!=null) socket.close(); } catch(IOException ignored) { }
        }
        @Override public void run() {
            try {
                socket=device.createRfcommSocketToServiceRecord(SPP);
                if(!running) return;
                socket.connect();
                if(!running) return;
                connected=true; main.removeCallbacks(connectTimeout);
                InputStream in=socket.getInputStream(); OutputStream out=socket.getOutputStream();
                Protocol.Parser parser=new Protocol.Parser();
                int token=0,sequence=0; boolean armed=false;
                while(running) {
                    long tick=SystemClock.elapsedRealtime();
                    if(tick-uiTick>300) throw new IOException("UI heartbeat expired");
                    Input value=input;
                    // Handshake/re-arm is always STOP; previous touches are discarded.
                    int command=armed?value.command:Protocol.STOP;
                    out.write(Protocol.request(token,++sequence,value.speed,command,value.lights));
                    byte[] ack=null;
                    long deadline=tick+300;
                    while(running && SystemClock.elapsedRealtime()<deadline && ack==null) {
                        int budget=256;
                        while(in.available()>0 && budget-->0) {
                            int b=in.read(); if(b<0) throw new EOFException();
                            byte[] candidate=parser.push(b);
                            if(candidate!=null && Protocol.matches(candidate,token,sequence)) { ack=candidate; break; }
                        }
                        if(ack==null) Thread.sleep(3);
                    }
                    if(!running) break;
                    if(ack==null) throw new IOException("No acknowledgement");
                    token=Protocol.number(ack,4); armed=ack[15]==1;
                    report(this,armed,armed?"Csatlakozva · TankCar":"Biztonságos élesítés…",ack[13]&0xff,ack[12]&0xff);
                    long wait=50-(SystemClock.elapsedRealtime()-tick);
                    if(wait>0) Thread.sleep(wait);
                }
            } catch(IOException | SecurityException | InterruptedException failure) {
                // The main thread schedules a fresh, neutral session for this MAC only.
            } finally {
                close();
                main.post(()->{
                    if(client==this) {
                        client=null; setReady(false,"Megszakadt · Újracsatlakozás…"); scheduleRetry();
                    }
                });
            }
        }
    }
    /** Vertical SeekBar with three snapping positions. */
    private final class VerticalSeekBar extends SeekBar {
        private final Paint ticks=new Paint(Paint.ANTI_ALIAS_FLAG);
        VerticalSeekBar() {
            super(MainActivity.this); setPadding(dp(12),dp(12),dp(12),dp(12));
            ticks.setColor(0xff79cbc4); ticks.setStrokeWidth(dp(1));
        }
        @Override protected synchronized void onMeasure(int width,int height) {
            super.onMeasure(height,width); setMeasuredDimension(getMeasuredHeight(),getMeasuredWidth());
        }
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) { super.onSizeChanged(h,w,oldh,oldw); }
        @Override protected void onDraw(Canvas canvas) {
            float length=getHeight()-getPaddingLeft()-getPaddingRight();
            for(int step=0;step<=getMax();step++) {
                float y=getHeight()-getPaddingLeft()-length*step/Math.max(1,getMax());
                canvas.drawLine(getWidth()/2f-dp(5),y,getWidth()/2f+dp(5),y,ticks);
            }
            canvas.save(); canvas.rotate(-90); canvas.translate(-getHeight(),0); super.onDraw(canvas); canvas.restore();
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if(!isEnabled()) return false;
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_UP) {
                float length=getHeight()-getPaddingLeft()-getPaddingRight();
                float fraction=(getHeight()-getPaddingLeft()-event.getY())/Math.max(1,length);
                setProgress(Math.max(0,Math.min(getMax(),Math.round(fraction*getMax()))));
                onSizeChanged(getWidth(),getHeight(),0,0);
                setPressed(action!=MotionEvent.ACTION_UP);
                if(action==MotionEvent.ACTION_UP) performClick();
                return true;
            }
            if(action==MotionEvent.ACTION_CANCEL) { setPressed(false); return true; }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
