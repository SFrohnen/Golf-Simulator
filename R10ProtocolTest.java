package com.privategolf.r10range;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure-Java regression tests: run with tests/run-tests.sh (no Android SDK needed). */
public final class R10ProtocolTest {
    static final class Sink implements R10Protocol.Events {
        final List<byte[]> packets = new ArrayList<>();
        final List<Boolean> register = new ArrayList<>();
        final List<String> phases = new ArrayList<>(), logs = new ArrayList<>();
        final List<R10Protocol.Shot> shots = new ArrayList<>();
        @Override public void write(boolean reg, byte[] chunk) { packets.add(chunk); register.add(reg); }
        @Override public void phase(String msg) { phases.add(msg); }
        @Override public void log(String msg) { logs.add(msg); }
        @Override public void shot(R10Protocol.Shot shot) { shots.add(shot); }
        void clear() {packets.clear(); register.clear();}
    }
    static void check(boolean v, String message) { if(!v) throw new AssertionError(message); }
    static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p:parts) out.writeBytes(p);
        return out.toByteArray();
    }
    static void put16(ByteArrayOutputStream b, long n) {b.write((int)n&255);b.write((int)(n>>8)&255);}
    static void put32(ByteArrayOutputStream b, long n) {for(int i=0;i<4;i++)b.write((int)(n>>(i*8))&255);}
    static byte[] notification(int type, byte[] payload) {
        return join(new byte[]{0},R10Protocol.cobsEncode(R10Protocol.makeFrame(type,payload)),new byte[]{0});
    }
    static void send(R10Protocol protocol, byte[] framed) {
        // Split data unevenly to exercise stream reassembly with handle prefix on every chunk.
        for(int i=0;i<framed.length;i+=7) {
            byte[] chunk=new byte[Math.min(7,framed.length-i)+1];chunk[0]=5;
            System.arraycopy(framed,i,chunk,1,chunk.length-1);
            protocol.notification(chunk);
        }
    }
    static byte[] gattPayload(byte[] protobuf) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        put16(out,1);put32(out,0);put32(out,protobuf.length);put32(out,protobuf.length);
        out.writeBytes(protobuf);
        return out.toByteArray();
    }
    static byte[] sentGfdi(Sink sink) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(byte[] chunk:sink.packets) {
            check(chunk[0]==5,"outbound handle");
            check(chunk.length<=20,"respects ATT MTU");
            out.write(chunk,1,chunk.length-1);
        }
        byte[] all=out.toByteArray();
        check(all.length>=3&&all[0]==0&&all[all.length-1]==0,"COBS delimiters");
        return R10Protocol.cobsDecode(Arrays.copyOfRange(all,1,all.length-1));
    }
    public static void main(String[] args) {
        check(R10Protocol.crc16("123456789".getBytes())==0xbb3d,"CRC16/ARC test vector");
        for(int n=0;n<600;n++) {
            byte[] src = new byte[n];for(int i=0;i<n;i++)src[i]=(byte)((i*13+n*3)%256);
            check(Arrays.equals(src,R10Protocol.cobsDecode(R10Protocol.cobsEncode(src))),"COBS length "+n);
        }
        Sink sink=new Sink();R10Protocol protocol=new R10Protocol(sink);
        protocol.start();
        check(sink.packets.size()==1&&sink.register.get(0),"register written to channel 2810");
        check(sink.packets.get(0).length==13&&sink.packets.get(0)[10]==1,"registration format");
        sink.clear();
        byte[] response=new byte[16];response[1]=1;response[2]=1;response[10]=1;response[13]=5;
        protocol.notification(response);
        check(sink.phases.get(sink.phases.size()-1).contains("registered"),"registration response");
        byte[] deviceInfo=new byte[15];deviceInfo[0]=(byte)150;
        send(protocol,notification(5024,deviceInfo));
        R10Protocol.Frame ack5024=R10Protocol.parseFrame(sentGfdi(sink));
        check(ack5024.type==5000&&ack5024.payload[0]==(byte)(5024&255),"5024 ACK");
        sink.clear();
        send(protocol,notification(5050,new byte[]{4,0,0,0,64}));
        check(!sink.packets.isEmpty(),"capabilities response queued");
        sink.clear();
        ByteArrayOutputStream ack=new ByteArrayOutputStream();put16(ack,5050);ack.write(0);
        send(protocol,notification(5000,ack.toByteArray()));
        R10Protocol.Frame subscribe=R10Protocol.parseFrame(sentGfdi(sink));
        check(subscribe.type==5043,"subscribe sent as protobuf request");
        sink.clear();
        byte[] accepted = R10Protocol.pbfBytes(30,R10Protocol.pbfBytes(2,R10Protocol.pbfBytes(1,R10Protocol.pbfNumber(1,0))));
        send(protocol,notification(5044,gattPayload(accepted)));
        check(sink.phases.stream().anyMatch(p->p.contains("Waking")),"wakeup phase");
        sink.clear();
        byte[] awake=R10Protocol.pbfBytes(38,R10Protocol.pbfBytes(4,R10Protocol.pbfNumber(1,1)));
        send(protocol,notification(5044,gattPayload(awake)));
        check(sink.phases.get(sink.phases.size()-1).contains("already awake"),"already awake event");
        sink.clear();
        byte[] ball=join(R10Protocol.pbfFloat(1,18.5f),R10Protocol.pbfFloat(2,-2.2f),
                R10Protocol.pbfFloat(3,51.0f),R10Protocol.pbfFloat(4,12.0f),
                R10Protocol.pbfFloat(5,5500.0f),R10Protocol.pbfNumber(6,3));
        byte[] club=join(R10Protocol.pbfFloat(1,39.4f),R10Protocol.pbfFloat(2,3.5f));
        byte[] shot=join(R10Protocol.pbfNumber(1,99),R10Protocol.pbfNumber(2,1),
                R10Protocol.pbfBytes(3,ball),R10Protocol.pbfBytes(4,club));
        byte[] report=R10Protocol.pbfBytes(2,shot);
        byte[] notif=R10Protocol.pbfBytes(30,R10Protocol.pbfBytes(3,R10Protocol.pbfBytes(1001,report)));
        send(protocol,notification(5043,gattPayload(notif)));
        check(sink.shots.size()==1,"shot parsed");
        R10Protocol.Shot decoded=sink.shots.get(0);
        check(decoded.shotId==99&&!decoded.practice&&decoded.hasBall,"shot id, type and ball");
        check(Math.abs(decoded.speedMps-51)<0.001&&Math.abs(decoded.verticalDeg-18.5)<0.001,"ball speed and launch");
        check(Math.abs(decoded.spinAxisDeg+12)<0.001&&decoded.spinSource==3,"spin sign and provenance");
        check(Math.abs(decoded.clubSpeedMps-39.4)<0.001,"club speed");
        send(protocol,notification(5043,gattPayload(notif)));
        check(sink.shots.size()==1,"duplicate shot not imported");
        byte[] corrupt=notification(5043,gattPayload(notif));
        corrupt[4]^=0x01;
        send(protocol,corrupt);
        check(!sink.logs.isEmpty(),"corrupted CRC/COBS logged without importing");
        System.out.println("PASS: CRC, COBS, BLE fragmentation, R10 registration, GFDI handshake, protobuf subscription/wake, shot parsing, deduplication, invalid frames");
    }
}
