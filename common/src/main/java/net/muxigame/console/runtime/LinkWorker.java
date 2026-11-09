package net.muxigame.console.runtime;

import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;

/** Dedicated authenticated loopback endpoint; core/JNA never executes in Minecraft's process. */
public final class LinkWorker {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[a-f0-9]{64}")) throw new IOException("Invalid link worker startup");
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), Integer.parseInt(args[0]));
             var input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             var output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            socket.setTcpNoDelay(true); LinkWire.text(output, args[1]); output.flush(); LinkBackend core = null;
            try {
                boolean quit = false;
                while (!quit) {
                    LinkWire.Packet packet;
                    try { packet = LinkWire.receive(input); } catch (EOFException ended) { break; }
                    byte[] response;
                    try {
                        var body = LinkWire.input(packet.body()); byte[] result;
                        if (packet.operation() == LinkWire.OPEN) {
                            if (core != null) throw new IOException("Link worker already opened");
                            Path library = Path.of(LinkWire.text(body)); String hash = LinkWire.text(body); Path system = Path.of(LinkWire.text(body));
                            List<LinkRuntime.Endpoint> endpoints = new ArrayList<>();
                            for (int i = 0; i < 2; i++) endpoints.add(new LinkRuntime.Endpoint(UUID.fromString(LinkWire.text(body)), LinkWire.text(body), LinkWire.text(body), Path.of(LinkWire.text(body))));
                            LinkWire.end(body);
                            LinkRuntime.Config config=new LinkRuntime.Config(Path.of(System.getProperty("java.home"), "bin", "java.exe"), List.of(Path.of(".")), library, hash, system, endpoints);
                            core = endpoints.get(0).platform().equals("gba")?new LinkGbaNativeCore(config):new LinkNativeCore(config);
                            CoreInfo info = core.info(); result = LinkWire.body(out -> { LinkWire.text(out, info.name()); LinkWire.text(out, info.version());out.writeInt(info.width());out.writeInt(info.height()); out.writeDouble(info.fps()); out.writeDouble(info.sampleRate()); });
                        } else {
                            if (core == null && packet.operation() != LinkWire.QUIT) throw new IOException("No opened link core");
                            result = switch (packet.operation()) {
                                case LinkWire.STEP -> {
                                    PortInput left = InputCodec.readPort(body), right = InputCodec.readPort(body); LinkWire.end(body);
                                    LinkRuntime.Frame frame = core.step(left, right);
                                    yield LinkWire.body(out -> { LinkWire.frame(out, frame.left()); LinkWire.frame(out, frame.right()); });
                                }
                                case LinkWire.EXPORT -> { int endpoint = body.readUnsignedByte(); LinkWire.end(body); yield core.exportEndpoint(endpoint); }
                                case LinkWire.RESTORE -> { int endpoint = body.readUnsignedByte(); byte[] state = LinkWire.bytes(body, 4 * 1024 * 1024); LinkWire.end(body); core.restoreEndpoint(endpoint, state); yield new byte[0]; }
                                case LinkWire.SAVE_LINK -> { LinkWire.end(body); yield core.saveLinkState(); }
                                case LinkWire.LOAD_LINK -> { core.loadLinkState(packet.body()); yield new byte[0]; }
                                case LinkWire.QUIT -> { LinkWire.end(body); quit = true; if (core != null) { core.close(); core = null; } yield new byte[0]; }
                                default -> throw new IOException("Unknown link worker operation");
                            };
                        }
                        response = LinkWire.body(out -> { out.writeByte(0); out.write(result); });
                    } catch (Exception | LinkageError error) {
                        String message = error instanceof IOException ? String.valueOf(error.getMessage()) : "Link worker rejected " + error.getClass().getSimpleName();
                        String bounded = message.length() > 4096 ? message.substring(0, 4096) : message;
                        response = LinkWire.body(out -> { out.writeByte(1); LinkWire.text(out, bounded); });
                    }
                    LinkWire.send(output, packet.operation() | 0x8000, packet.sequence(), response);
                }
            } finally { if (core != null) core.close(); }
        }
    }
}
