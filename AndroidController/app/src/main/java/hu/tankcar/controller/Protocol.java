package hu.tankcar.controller;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/** Shared wire format: TB01, session, sequence, speed, command, lights, armed, CRC32. */
final class Protocol {
    static final int SIZE=20, STOP=0, FORWARD=1, REVERSE=2, LEFT=3, RIGHT=4;
    static byte[] request(int token,int sequence,int speed,int command,boolean lights) {
        if(speed<1 || speed>10 || command<STOP || command>RIGHT) throw new IllegalArgumentException();
        byte[] p=ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(new byte[]{'T','B','0','1'}).putInt(token).putInt(sequence)
            .put((byte)speed).put((byte)command).put((byte)(lights?1:0)).put((byte)0).putInt(0).array();
        seal(p); return p;
    }
    static int number(byte[] p,int offset) { return ByteBuffer.wrap(p).getInt(offset); }
    static void seal(byte[] p) {
        CRC32 crc=new CRC32(); crc.update(p,0,16);
        ByteBuffer.wrap(p).putInt(16,(int)crc.getValue());
    }
    static boolean valid(byte[] p) {
        if(p.length!=SIZE || p[0]!='T' || p[1]!='B' || p[2]!='0' || p[3]!='1') return false;
        CRC32 crc=new CRC32(); crc.update(p,0,16);
        return number(p,16)==(int)crc.getValue();
    }
    static boolean matches(byte[] reply,int token,int sequence) {
        return valid(reply) && number(reply,4)!=0 && (token==0 || number(reply,4)==token)
            && number(reply,8)==sequence && reply[15]>=0 && reply[15]<=1;
    }
    static int command(int held) {
        // Conflicting directions stop, including forward + pivot.
        if(Integer.bitCount(held)!=1) return STOP;
        return Integer.numberOfTrailingZeros(held)+1;
    }
    static final class Parser {
        private final byte[] data=new byte[SIZE];
        private int used;
        byte[] push(int b) {
            data[used++]=(byte)b;
            if(used<SIZE) return null;
            if(valid(data)) { used=0; return data.clone(); }
            System.arraycopy(data,1,data,0,--used); return null;
        }
    }
}
