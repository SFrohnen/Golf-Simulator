package com.privategolf.r10range;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Independent, dependency-free Java implementation of the documented R10
 * MultiLink / GFDI / COBS / CRC16 / protobuf-shot exchange.
 *
 * This is a candidate interoperability implementation, not a hardware-verified driver.
 * Wire reference: https://github.com/divotmaker/10over/blob/main/docs/WIRE.md
 * Sequence: https://github.com/divotmaker/10over/blob/main/docs/SEQUENCE.md
 * Never record raw identity / authentication or pre-handshake frames in exported logs.
 */
public final class R10Protocol {
    public interface Events {
        void write(boolean registerChannel, byte[] chunk);
        void phase(String name);
        void log(String message);
        void shot(Shot shot);
    }
    public static final class Shot {
        public long shotId;
        public boolean practice;
        public boolean hasBall;
        public double speedMps, verticalDeg, horizontalDeg, spinRpm, spinAxisDeg;
        public double clubSpeedMps, faceDeg, pathDeg, attackDeg;
        public int spinSource;
        public boolean hasClub;
    }
    private enum Phase { IDLE, REGISTERING, DEVICE_INFO, CAPABILITIES, CAPS_ACK, SUBSCRIBING, WAKING, ACTIVE }
    private Phase phase = Phase.IDLE;
    private final Events events;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private boolean inFrame = false;
    private int handle, requestId = 1;
    private String lastRadarPhase = "";
    private final LinkedHashMap<Long, Long> recentShots = new LinkedHashMap<>();
    private static final int MAX_FRAME = 8192;

    public R10Protocol(Events events) { this.events = events; }

    /** Call only after CCCD notifications are enabled. */
    public void start() {
        phase = Phase.REGISTERING;
        handle = 0;
        inFrame = false;
        pending.reset();
        recentShots.clear();
        requestId = 1;
        byte[] reg = new byte[13];
        reg[2] = 1; // transaction id = 1, little endian u64
        reg[10] = 1; // GFDI service id = 1, little endian u16
        events.phase("Registering R10 MultiLink...");
        events.write(true, reg);
    }

    /** One BLE notification, with its MultiLink handle prefix. */
    public void notification(byte[] packet) {
        if (packet == null || packet.length == 0) return;
        if (phase == Phase.REGISTERING && packet.length >= 16 && packet[0] == 0 && packet[1] == 1 &&
                u16(packet, 10) == 1 && packet[2] == 1) {
            if (packet[12] != 0) {
                events.phase("R10 registration rejected (code " + (packet[12] & 255) + ")");
                return;
            }
            handle = packet[13] & 255;
            if (handle == 0) { events.phase("Invalid MultiLink handle"); return; }
            phase = Phase.DEVICE_INFO;
            events.phase("R10 registered, awaiting device handshake...");
            return;
        }
        if (handle == 0 || (packet[0] & 255) != handle) return;
        for (int i = 1; i < packet.length; i++) {
            int b = packet[i] & 255;
            if (b == 0) {
                if (inFrame && pending.size() != 0) {
                    byte[] encoded = pending.toByteArray();
                    pending.reset();
                    try { dispatch(parseFrame(cobsDecode(encoded))); }
                    catch (IllegalArgumentException ex) { events.log("Discarded invalid GFDI packet: " + ex.getMessage()); }
                }
                inFrame = true;
            } else if (inFrame) {
                if (pending.size() >= MAX_FRAME) { pending.reset(); inFrame = false; events.log("Oversized frame discarded"); }
                else pending.write(b);
            }
        }
    }

    private void dispatch(Frame f) {
        // Never expose raw 5024 identity, FIT payloads, or authentication material in logs.
        switch (f.type) {
            case 5024:
                if (phase != Phase.DEVICE_INFO || f.payload.length < 12) return;
                int version = u16(f.payload, 0);
                ByteArrayOutputStream host = new ByteArrayOutputStream();
                put16(host, version >= 150 ? 150 : 113);
                put16(host, 0xffff);
                put32(host, 0xffffffffL);
                put16(host, 100);
                put16(host, 0xffff);
                putString(host, "Private Range");
                putString(host, "Android");
                putString(host, "R10 Client");
                host.write(1);
                ack(5024, host.toByteArray());
                phase = Phase.CAPABILITIES;
                events.phase("GFDI handshake: device info accepted");
                break;
            case 5011:
            case 5012:
                ack(f.type, new byte[]{0});
                break;
            case 5050:
                if (phase != Phase.CAPABILITIES) return;
                ack(5050, new byte[0]);
                byte[] capabilities = new byte[14];
                capabilities[0] = 13;
                capabilities[1 + 30 / 8] = (byte)(1 << (30 % 8));
                sendFrame(5050, capabilities);
                phase = Phase.CAPS_ACK;
                events.phase("GFDI handshake: exchanging capabilities...");
                break;
            case 5000:
                if (f.payload.length >= 3) {
                    int original = u16(f.payload, 0);
                    int status = f.payload[2] & 255;
                    if (status != 0) { events.phase("R10 rejected GFDI " + original + " (" + status + ")"); return; }
                    if (phase == Phase.CAPS_ACK && original == 5050) {
                        phase = Phase.SUBSCRIBING;
                        sendProtobuf(subscribeMessage());
                        events.phase("Handshake complete. Subscribing to shot data...");
                    }
                }
                break;
            case 5043:
            case 5044:
                try { decodeApplication(f.payload); }
                catch (IllegalArgumentException e) { events.log("Invalid R10 protobuf: " + e.getMessage()); }
                ack(f.type, new byte[0]);
                break;
            default:
                // Identity-bearing messages and unknown types are ignored.
        }
    }

    private void decodeApplication(byte[] payload) {
        if (payload.length < 14) throw new IllegalArgumentException("missing fragment header");
        long offset = u32(payload, 2), total = u32(payload, 6), length = u32(payload, 10);
        if (total > MAX_FRAME || offset != 0 || total != length || length > payload.length - 14) {
            events.log("Unsupported fragment (offset/length mismatch)");
            return;
        }
        Pbf root = new Pbf(Arrays.copyOfRange(payload, 14, 14 + (int)length));
        Pbf alerts = root.child(30);
        if (alerts != null) {
            Pbf subscribeResponse = alerts.child(2);
            if (subscribeResponse != null && phase == Phase.SUBSCRIBING) {
                Pbf outcome = subscribeResponse.child(1);
                if (outcome == null || outcome.number(1, 1) != 0) {
                    events.phase("R10 rejected shot subscription"); return;
                }
                phase = Phase.WAKING;
                sendProtobuf(wakeupMessage());
                events.phase("Subscribed. Waking R10 radar...");
            }
            Pbf notify = alerts.child(3);
            if (notify != null) {
                Pbf report = notify.child(1001);
                if (report != null) decodeReport(report);
            }
        }
        Pbf monitor = root.child(38);
        if (monitor != null) {
            Pbf wakeResponse = monitor.child(4);
            if (wakeResponse != null) {
                int status = (int) wakeResponse.number(1, 0);
                if (status <= 1) {
                    phase = Phase.ACTIVE;
                    events.phase(status == 1 ? "R10 already awake — ready for shots" : "R10 awake — waiting for radar to arm");
                } else events.phase("R10 failed to wake: " + status);
            }
        }
    }

    private void decodeReport(Pbf report) {
        Pbf status = report.child(1);
        if (status != null) {
            String[] names = {"Idle", "Noise check", "Armed — ready", "Recording", "Processing", "Device fault"};
            int code = (int)status.number(1, -1);
            if (code >= 0 && code < names.length && !names[code].equals(lastRadarPhase)) {
                lastRadarPhase = names[code];
                events.phase("R10 radar: " + lastRadarPhase);
                if (code == 0 && phase == Phase.ACTIVE) sendProtobuf(wakeupMessage());
            }
        }
        Pbf fault = report.child(3);
        if (fault != null) events.phase("R10 radar fault (code " + fault.number(1, -1) + ") — check alignment and surroundings");
        Pbf s = report.child(2);
        if (s == null) return;
        long id = s.number(1, 0);
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Long,Long>> it = recentShots.entrySet().iterator();
        while (it.hasNext()) if (now - it.next().getValue() > 60_000) it.remove();
        if (recentShots.containsKey(id)) return;
        recentShots.put(id, now);
        Shot shot = new Shot();
        shot.shotId = id;
        shot.practice = s.number(2, 0) == 0;
        Pbf b = s.child(3), c = s.child(4);
        if (b != null) {
            shot.hasBall = true;
            shot.verticalDeg = b.decimal(1, 0);
            shot.horizontalDeg = b.decimal(2, 0);
            shot.speedMps = b.decimal(3, 0);
            shot.spinAxisDeg = -b.decimal(4, 0); // + means right curve in UI
            shot.spinRpm = b.decimal(5, 0);
            shot.spinSource = (int)b.number(6, 0);
        }
        if (c != null) {
            shot.hasClub = true;
            shot.clubSpeedMps = c.decimal(1, 0);
            shot.faceDeg = c.decimal(2, 0);
            shot.pathDeg = c.decimal(3, 0);
            shot.attackDeg = c.decimal(4, 0);
        }
        if (shot.hasBall && shot.speedMps > 0 && Double.isFinite(shot.speedMps)) events.shot(shot);
        else events.log("R10 practice swing #" + id + " (no ball departure)");
    }

    private void ack(int original, byte[] extra) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put16(out, original);
        out.write(0);
        append(out, extra);
        sendFrame(5000, out.toByteArray());
    }
    private void sendProtobuf(byte[] pb) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        put16(payload, requestId++ & 65535);
        put32(payload, 0);
        put32(payload, pb.length);
        put32(payload, pb.length);
        append(payload, pb);
        sendFrame(5043, payload.toByteArray());
    }
    private void sendFrame(int type, byte[] payload) {
        byte[] raw = makeFrame(type, payload);
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        framed.write(0);
        append(framed, cobsEncode(raw));
        framed.write(0);
        byte[] bytes = framed.toByteArray();
        // Keep chunks within default ATT MTU=23 (20 bytes ATT value, of which 1 is handle).
        for (int start = 0; start < bytes.length; start += 19) {
            int n = Math.min(19, bytes.length - start);
            byte[] chunk = new byte[n + 1];
            chunk[0] = (byte)handle;
            System.arraycopy(bytes, start, chunk, 1, n);
            events.write(false, chunk);
        }
    }
    static byte[] makeFrame(int type, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put16(out, payload.length + 6);
        put16(out, type);
        append(out, payload);
        int crc = crc16(out.toByteArray());
        put16(out, crc);
        return out.toByteArray();
    }
    private static byte[] subscribeMessage() {
        return pbfBytes(30, pbfBytes(1, pbfBytes(1, pbfNumber(1, 8))));
    }
    private static byte[] wakeupMessage() {
        return pbfBytes(38, pbfBytes(3, new byte[0]));
    }
    static byte[] pbfNumber(int number, long value) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        varint(result, number * 8L);
        varint(result, value);
        return result.toByteArray();
    }
    static byte[] pbfFloat(int number, float value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varint(out, number * 8L + 5);
        put32(out, Float.floatToIntBits(value));
        return out.toByteArray();
    }
    static byte[] pbfBytes(int number, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varint(out, number * 8L + 2);
        varint(out, value.length);
        append(out, value);
        return out.toByteArray();
    }
    private static void varint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) { out.write(((int)value & 127) | 128); value >>>= 7; }
        out.write((int)value);
    }
    private static void append(ByteArrayOutputStream out, byte[] data) { out.write(data, 0, data.length); }
    private static void putString(ByteArrayOutputStream out, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        out.write(bytes.length);
        append(out, bytes);
    }
    private static void put16(ByteArrayOutputStream out, long n) { out.write((int)n & 255); out.write((int)(n >> 8) & 255); }
    private static void put32(ByteArrayOutputStream out, long n) {
        out.write((int)n & 255); out.write((int)(n >> 8) & 255); out.write((int)(n >> 16) & 255); out.write((int)(n >> 24) & 255);
    }
    private static int u16(byte[] b, int i) { return (b[i] & 255) | ((b[i+1] & 255) << 8); }
    private static long u32(byte[] b, int i) { return ((long)u16(b,i)) | ((long)u16(b,i+2) << 16); }
    static int crc16(byte[] data) {
        int crc = 0;
        for (byte b: data) {
            crc ^= (b & 255);
            for (int j=0;j<8;j++) crc = ((crc & 1) != 0) ? ((crc >>> 1) ^ 0xa001) : crc >>> 1;
        }
        return crc & 65535;
    }
    static byte[] cobsEncode(byte[] input) {
        byte[] buffer = new byte[input.length + input.length / 254 + 3];
        int codeIndex = 0, out = 1, code = 1;
        for (byte value: input) {
            if (value == 0) {
                buffer[codeIndex] = (byte) code;
                codeIndex = out++;
                code = 1;
            } else {
                buffer[out++] = value;
                code++;
                if (code == 255) {
                    buffer[codeIndex] = (byte) code;
                    codeIndex = out++;
                    code = 1;
                }
            }
        }
        buffer[codeIndex] = (byte) code;
        return Arrays.copyOf(buffer, out);
    }
    static byte[] cobsDecode(byte[] encoded) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int index = 0; index < encoded.length;) {
            int code = encoded[index++] & 255;
            if (code == 0 || index + code - 1 > encoded.length) throw new IllegalArgumentException("COBS length");
            out.write(encoded, index, code - 1);
            index += code - 1;
            if (code != 255 && index < encoded.length) out.write(0);
        }
        return out.toByteArray();
    }
    static Frame parseFrame(byte[] raw) {
        if (raw.length < 6 || u16(raw, 0) != raw.length) throw new IllegalArgumentException("GFDI length");
        if (u16(raw, raw.length - 2) != crc16(Arrays.copyOf(raw, raw.length - 2)))
            throw new IllegalArgumentException("GFDI CRC");
        int type = (raw[3] & 0x80) != 0 ? 5000 + (raw[2] & 255) : u16(raw, 2);
        return new Frame(type, Arrays.copyOfRange(raw, 4, raw.length - 2));
    }
    static final class Frame {
        final int type; final byte[] payload;
        Frame(int type, byte[] payload) { this.type=type; this.payload=payload; }
    }

    /** Tiny protobuf wire reader: no external dependencies, bounds-checked. */
    static final class Pbf {
        final Map<Integer, List<Value>> fields = new HashMap<>();
        static final class Value {
            int wire; long n; byte[] bytes;
            Value(int wire, long n, byte[] bytes) {this.wire=wire;this.n=n;this.bytes=bytes;}
        }
        Pbf(byte[] data) {
            int p = 0, count = 0;
            while (p < data.length) {
                if (++count > 512) throw new IllegalArgumentException("too many protobuf fields");
                long[] k = readVarint(data, p); p = (int)k[1];
                int field = (int)(k[0] >>> 3), wire = (int)(k[0] & 7);
                if (field < 1) throw new IllegalArgumentException("invalid field number");
                long val = 0; byte[] bytes = null;
                switch(wire) {
                    case 0:
                        k=readVarint(data,p); val=k[0]; p=(int)k[1]; break;
                    case 1:
                        if (p+8 > data.length) throw new IllegalArgumentException("truncated fixed64");
                        p+=8; break;
                    case 2:
                        k=readVarint(data,p); p=(int)k[1];
                        if (k[0] > data.length - p || k[0] > MAX_FRAME) throw new IllegalArgumentException("truncated length-delimited");
                        bytes=Arrays.copyOfRange(data,p,p+(int)k[0]); p+=(int)k[0]; break;
                    case 5:
                        if (p+4 > data.length) throw new IllegalArgumentException("truncated fixed32");
                        val=u32(data,p); p+=4; break;
                    default: throw new IllegalArgumentException("unsupported protobuf wire type " + wire);
                }
                fields.computeIfAbsent(field, x->new ArrayList<>()).add(new Value(wire,val,bytes));
            }
        }
        Pbf child(int field) {
            Value v = first(field); return v == null || v.wire != 2 ? null : new Pbf(v.bytes);
        }
        long number(int field, long fallback) {Value v=first(field);return v == null || v.wire!=0 ? fallback : v.n;}
        double decimal(int field, double fallback) {Value v=first(field);return v == null || v.wire!=5 ? fallback : Float.intBitsToFloat((int)v.n);}
        private Value first(int field) {List<Value> l=fields.get(field);return l==null||l.isEmpty()?null:l.get(0);}
        private static long[] readVarint(byte[] d, int from) {
            long v=0; int shift=0;
            for (int i=from;i<d.length && shift<70;i++,shift+=7) {
                int b=d[i]&255;
                v |= (long)(b&127) << shift;
                if ((b&128)==0) return new long[]{v, i+1};
            }
            throw new IllegalArgumentException("truncated/oversized varint");
        }
    }
}
