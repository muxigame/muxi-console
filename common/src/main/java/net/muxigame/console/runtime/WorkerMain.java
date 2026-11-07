package net.muxigame.console.runtime;

import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;

/** Process entry point. Every libretro call and callback stays on this one thread. */
public final class WorkerMain {
    private WorkerMain() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Worker requires a private endpoint and token");
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), Integer.parseInt(args[0]));
             var in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            socket.setTcpNoDelay(true); Wire.string(out, args[1]); out.flush();
            LibretroCore core = null;
            try {
                boolean quit = false;
                while (!quit) {
                    Wire.Packet packet;
                    try { packet = Wire.read(in); } catch (EOFException ex) { break; }
                    byte[] response;
                    try {
                        var body = Wire.input(packet.body()); byte[] result;
                        if (packet.operation() == Wire.OPEN) {
                            if (core != null) throw new IOException("Core already loaded");
                            Path library = Path.of(Wire.string(body)); String content = Wire.string(body);
                            Path system = Path.of(Wire.string(body)), save = Path.of(Wire.string(body));
                            int count = body.readInt(); if (count < 0 || count > 256) throw new IOException("Invalid option count");
                            Map<String, String> options = new HashMap<>();
                            for (int i = 0; i < count; i++) if (options.put(Wire.string(body), Wire.string(body)) != null) throw new IOException("Duplicate core option");
                            Wire.end(body);
                            core = new LibretroCore(library, content.isEmpty() ? null : Path.of(content), system, save, options);
                            LibretroCore opened = core; result = Wire.body(reply -> Wire.info(reply, opened.info()));
                        } else {
                            if (core == null && packet.operation() != Wire.QUIT) throw new IOException("No loaded core");
                            result = switch (packet.operation()) {
                                case Wire.STEP -> {
                                    int ports = body.readUnsignedByte(); if (ports > 4) throw new IOException("Too many controller ports");
                                    List<PortInput> inputs = new ArrayList<>();
                                    for (int i = 0; i < ports; i++) inputs.add(new PortInput(body.readUnsignedShort(), body.readShort(), body.readShort(), body.readShort(), body.readShort()));
                                    Wire.end(body); AvFrame frame = core.step(new InputFrame(inputs)); yield Wire.body(reply -> Wire.frame(reply, frame));
                                }
                                case Wire.RESET -> { Wire.end(body); core.reset(); yield new byte[0]; }
                                case Wire.SAVE -> { Wire.end(body); yield core.saveState(); }
                                case Wire.LOAD -> { core.loadState(packet.body()); yield new byte[0]; }
                                case Wire.SRAM_READ -> { Wire.end(body); yield core.readSram(); }
                                case Wire.SRAM_WRITE -> { core.writeSram(packet.body()); yield new byte[0]; }
                                case Wire.DISK_STATE -> { Wire.end(body); DiskState state = core.diskState(); yield Wire.body(reply -> { reply.writeInt(state.count()); reply.writeInt(state.index()); reply.writeBoolean(state.ejected()); }); }
                                case Wire.DISK_SELECT -> { int index = body.readInt(); Wire.end(body); core.selectDisk(index); yield new byte[0]; }
                                case Wire.DISK_EJECT -> { int ejected = body.readUnsignedByte(); Wire.end(body); if (ejected > 1) throw new IOException("Invalid eject state"); core.setDiskEjected(ejected != 0); yield new byte[0]; }
                                case Wire.QUIT -> { Wire.end(body); if (core != null) { core.close(); core = null; } quit = true; yield new byte[0]; }
                                default -> throw new IOException("Unknown worker operation");
                            };
                        }
                        response = Wire.body(reply -> { reply.writeByte(0); reply.write(result); });
                    } catch (Exception | LinkageError ex) {
                        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                        response = Wire.body(reply -> { reply.writeByte(1); Wire.string(reply, message.length() > 4096 ? message.substring(0, 4096) : message); });
                    }
                    Wire.write(out, packet.operation() | 0x8000, packet.request(), response);
                }
            } finally { if (core != null) core.close(); }
        }
    }
}
