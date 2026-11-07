package net.muxigame.console.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Independently defined MXCW v2 protocol, network byte order throughout. */
final class Wire {
    static final int MAGIC = 0x4d584357, VERSION = 2, MAX_BYTES = 64 * 1024 * 1024;
    static final int OPEN = 1, STEP = 2, RESET = 3, SAVE = 4, LOAD = 5, SRAM_READ = 6, SRAM_WRITE = 7, QUIT = 8;
    static final int DISK_STATE = 9, DISK_SELECT = 10, DISK_EJECT = 11;
    record Packet(int operation, long request, byte[] body) {}
    interface Writer { void write(DataOutputStream out) throws IOException; }
    static byte[] body(Writer writer) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) { writer.write(out); }
        if (buffer.size() > MAX_BYTES) throw new IOException("Worker payload exceeds 64 MiB");
        return buffer.toByteArray();
    }
    static DataInputStream input(byte[] body) { return new DataInputStream(new ByteArrayInputStream(body)); }
    static void write(DataOutputStream out, int operation, long request, byte[] body) throws IOException {
        if (body.length > MAX_BYTES) throw new IOException("Worker payload exceeds 64 MiB");
        out.writeInt(MAGIC); out.writeShort(VERSION); out.writeShort(operation);
        out.writeLong(request); out.writeInt(body.length); out.write(body); out.flush();
    }
    static Packet read(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC || in.readUnsignedShort() != VERSION) throw new IOException("Invalid worker protocol");
        int operation = in.readUnsignedShort(); long request = in.readLong();
        return new Packet(operation, request, bytes(in));
    }
    static byte[] bytes(DataInputStream in) throws IOException {
        return bytes(in, MAX_BYTES);
    }
    private static byte[] bytes(DataInputStream in, int maximum) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > maximum)
            throw new IOException("Invalid worker byte count: " + size);
        byte[] result = in.readNBytes(size);
        if (result.length != size) throw new EOFException("Truncated worker payload");
        return result;
    }
    static void bytes(DataOutputStream out, byte[] value) throws IOException {
        if (value.length > MAX_BYTES) throw new IOException("Worker byte count exceeds limit");
        out.writeInt(value.length); out.write(value);
    }
    static String string(DataInputStream in) throws IOException {
        byte[] raw = bytes(in, 32768);
        return new String(raw, StandardCharsets.UTF_8);
    }
    static void string(DataOutputStream out, String value) throws IOException {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        if (raw.length > 32768) throw new IOException("Worker string too long");
        bytes(out, raw);
    }
    static void end(DataInputStream in) throws IOException { if (in.available() != 0) throw new IOException("Unexpected worker payload suffix"); }
    static void info(DataOutputStream out, CoreInfo info) throws IOException {
        string(out, info.name()); string(out, info.version()); out.writeInt(info.width()); out.writeInt(info.height());
        out.writeDouble(info.fps()); out.writeDouble(info.sampleRate()); out.writeDouble(info.displayAspect());
    }
    static CoreInfo info(DataInputStream in) throws IOException {
        try { return new CoreInfo(string(in), string(in), in.readInt(), in.readInt(), in.readDouble(), in.readDouble(), in.readDouble()); }
        catch (IllegalArgumentException ex) { throw new IOException("Invalid core information", ex); }
    }
    static void frame(DataOutputStream out, AvFrame frame) throws IOException {
        out.writeLong(frame.number()); out.writeInt(frame.width()); out.writeInt(frame.height()); out.writeInt(frame.rotation());
        out.writeDouble(frame.sampleRate()); out.writeBoolean(frame.shutdown()); bytes(out, frame.rgba());
        out.writeInt(frame.stereoPcm().length);
        for (short sample : frame.stereoPcm()) out.writeShort(sample);
        out.writeDouble(frame.displayAspect());
    }
    static AvFrame frame(DataInputStream in) throws IOException {
        long number = in.readLong(); int width = in.readInt(), height = in.readInt(), rotation = in.readInt();
        double rate = in.readDouble(); boolean shutdown = in.readBoolean(); byte[] rgba = bytes(in);
        int count = in.readInt();
        if (count < 0 || count > 192000 * 4 || count * 2L > in.available() || (count & 1) != 0)
            throw new IOException("Invalid audio sample count");
        short[] audio = new short[count]; for (int i = 0; i < count; i++) audio[i] = in.readShort();
        double aspect = in.readDouble();
        try { return new AvFrame(number, width, height, rotation, rgba, audio, rate, shutdown, aspect); }
        catch (IllegalArgumentException ex) { throw new IOException("Invalid worker frame", ex); }
    }
}
