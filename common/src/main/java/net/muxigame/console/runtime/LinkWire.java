package net.muxigame.console.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Independent link transport. Controller structure is owned by the shared InputCodec. */
final class LinkWire {
    static final int MAGIC = 0x4d584c4b, VERSION = 2, MAX_BYTES = 8 * 1024 * 1024;
    static final int OPEN = 1, STEP = 2, EXPORT = 3, RESTORE = 4, SAVE_LINK = 5, LOAD_LINK = 6, QUIT = 7;
    record Packet(int operation, long sequence, byte[] body) { }
    interface Writer { void write(DataOutputStream out) throws IOException; }
    static byte[] body(Writer writer) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) { writer.write(out); }
        if (bytes.size() > MAX_BYTES) throw new IOException("Link payload exceeds limit");
        return bytes.toByteArray();
    }
    static void send(DataOutputStream out, int operation, long sequence, byte[] body) throws IOException {
        if (body.length > MAX_BYTES) throw new IOException("Link payload exceeds limit");
        out.writeInt(MAGIC); out.writeShort(VERSION); out.writeShort(operation); out.writeLong(sequence); bytes(out, body); out.flush();
    }
    static Packet receive(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC || in.readUnsignedShort() != VERSION) throw new IOException("Invalid link protocol");
        return new Packet(in.readUnsignedShort(), in.readLong(), bytes(in, MAX_BYTES));
    }
    static DataInputStream input(byte[] bytes) { return new DataInputStream(new ByteArrayInputStream(bytes)); }
    static void end(DataInputStream in) throws IOException { if (in.available() != 0) throw new IOException("Trailing link payload"); }
    static void bytes(DataOutputStream out, byte[] value) throws IOException {
        if (value.length > MAX_BYTES) throw new IOException("Link byte count exceeds limit"); out.writeInt(value.length); out.write(value);
    }
    static byte[] bytes(DataInputStream in, int maximum) throws IOException {
        int length = in.readInt(); if (length < 0 || length > maximum) throw new IOException("Invalid link byte count");
        byte[] bytes = in.readNBytes(length); if (bytes.length != length) throw new EOFException("Truncated link payload"); return bytes;
    }
    static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); if (bytes.length > 32768) throw new IOException("Link string exceeds limit"); bytes(out, bytes);
    }
    static String text(DataInputStream in) throws IOException { return new String(bytes(in, 32768), StandardCharsets.UTF_8); }
    static void frame(DataOutputStream out, AvFrame frame) throws IOException {
        out.writeLong(frame.number());out.writeInt(frame.width());out.writeInt(frame.height());out.writeInt(frame.rotation());out.writeDouble(frame.displayAspect());out.writeDouble(frame.sampleRate()); out.writeBoolean(frame.shutdown());
        out.writeInt(frame.surfaces().size());for(var surface:frame.surfaces()){text(out,surface.id());out.writeInt(surface.x());out.writeInt(surface.y());out.writeInt(surface.width());out.writeInt(surface.height());out.writeInt(surface.nativeWidth());out.writeInt(surface.nativeHeight());out.writeBoolean(surface.touchable());}
        bytes(out, frame.rgba());
        out.writeInt(frame.stereoPcm().length); for (short sample : frame.stereoPcm()) out.writeShort(sample);
    }
    static AvFrame frame(DataInputStream in) throws IOException {
        long number = in.readLong();int width=in.readInt(),height=in.readInt(),rotation=in.readInt();double aspect=in.readDouble();double rate = in.readDouble(); boolean shutdown = in.readBoolean();
        if(width<1||width>512||height<1||height>768||rotation<0||rotation>3||!Double.isFinite(aspect)||aspect<=0||aspect>16)throw new IOException("Invalid link image geometry");
        int countSurfaces=in.readInt();if(countSurfaces<0||countSurfaces>3)throw new IOException("Invalid link surfaces");java.util.List<AvFrame.Surface> surfaces=new java.util.ArrayList<>();
        for(int i=0;i<countSurfaces;i++)surfaces.add(new AvFrame.Surface(text(in),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readBoolean()));
        byte[] rgba = bytes(in, 512 * 768 * 4); if (rgba.length != width*height*4) throw new IOException("Invalid link endpoint image");
        int count = in.readInt(); if (count < 0 || count > 8192 || (count & 1) != 0 || !Double.isFinite(rate) || rate < 8000 || rate > 192000)
            throw new IOException("Invalid GB endpoint audio");
        short[] pcm = new short[count]; for (int i = 0; i < count; i++) pcm[i] = in.readShort();
        return new AvFrame(number, width, height, rotation, rgba, pcm, rate, shutdown, aspect,surfaces);
    }
}
