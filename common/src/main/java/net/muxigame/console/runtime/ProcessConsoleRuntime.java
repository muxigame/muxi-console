package net.muxigame.console.runtime;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** The Minecraft-facing implementation; this class has no native/JNA dependency. */
public final class ProcessConsoleRuntime implements ConsoleRuntime {
    private final Process worker;
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final CoreInfo info;
    private final NativeTemp nativeTemp;
    private long nextRequest;
    private boolean closed, forcedShutdown;
    private volatile boolean nativeTempCleaned;
    private record NativeTemp(Path parent, Path path, Object fileKey) { }

    public static ProcessConsoleRuntime open(LaunchConfig config) throws IOException {
        requireFile(config.javaExecutable(), "Java executable"); requireFile(config.core(), "Emulator core");
        if (config.content() != null) requireFile(config.content(), "Game content");
        Files.createDirectories(config.systemDirectory()); Files.createDirectories(config.saveDirectory());
        Path parent = config.core().toAbsolutePath().normalize().getParent().toRealPath();
        Path path = Files.createTempDirectory(parent, "muxi-console-worker-").toAbsolutePath().normalize();
        NativeTemp temp = new NativeTemp(parent, path, Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey());
        Process process = null; Socket connection = null;
        byte[] tokenBytes = new byte[32]; new SecureRandom().nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
            server.setSoTimeout(15000);
            String classpath = config.classpath().stream().map(p -> p.toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
            List<String> command = List.of(config.javaExecutable().toAbsolutePath().toString(), "-Xms32m", "-Xmx256m",
                    "-XX:ActiveProcessorCount=1", "-Djna.tmpdir=" + temp.path, "-cp", classpath,
                    "net.muxigame.console.runtime.WorkerMain", Integer.toString(server.getLocalPort()), token);
            process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            connection = server.accept(); connection.setSoTimeout(15000); connection.setTcpNoDelay(true);
            var in = new DataInputStream(new BufferedInputStream(connection.getInputStream()));
            var out = new DataOutputStream(new BufferedOutputStream(connection.getOutputStream()));
            if (!token.equals(Wire.string(in))) throw new IOException("Worker authentication failed");
            return new ProcessConsoleRuntime(process, connection, in, out, config, temp);
        } catch (IOException | RuntimeException ex) {
            if (connection != null) try { connection.close(); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            if (process != null) {
                // EOF lets a responsive worker unload and exit before any termination.
                awaitExit(process, 2000);
                if (process.isAlive()) {
                    ex.addSuppressed(new IOException("Startup required termination of owned worker " + process.pid()));
                    process.destroy(); awaitExit(process, 2000);
                    if (process.isAlive()) { process.destroyForcibly(); awaitExit(process, 2000); }
                }
            }
            if (process == null || !process.isAlive()) try { cleanNativeTemp(temp); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            else ex.addSuppressed(new IOException("Owned worker still running; native temporary directory retained: " + process.pid()));
            throw ex;
        }
    }

    private ProcessConsoleRuntime(Process worker, Socket socket, DataInputStream input, DataOutputStream output, LaunchConfig config, NativeTemp nativeTemp) throws IOException {
        this.worker = worker; this.socket = socket; this.input = input; this.output = output;
        this.nativeTemp = nativeTemp;
        byte[] result = request(Wire.OPEN, Wire.body(out -> {
            Wire.string(out, config.core().toAbsolutePath().toString());
            Wire.string(out, config.content() == null ? "" : config.content().toAbsolutePath().toString());
            Wire.string(out, config.systemDirectory().toAbsolutePath().toString());
            Wire.string(out, config.saveDirectory().toAbsolutePath().toString());
            out.writeInt(config.options().size());
            for (var entry : config.options().entrySet()) { Wire.string(out, entry.getKey()); Wire.string(out, entry.getValue()); }
        }));
        var body = Wire.input(result); info = Wire.info(body); Wire.end(body);
    }

    @Override public CoreInfo info() { return info; }
    public long workerPid() { return worker.pid(); }
    public boolean isAlive() { return worker.isAlive(); }
    public OptionalInt exitCode() { return worker.isAlive() ? OptionalInt.empty() : OptionalInt.of(worker.exitValue()); }
    public boolean wasForcedShutdown() { return forcedShutdown; }
    public Path nativeTemporaryDirectory() { return nativeTemp.path; }
    public boolean nativeTemporaryFilesCleaned() { return nativeTempCleaned; }

    @Override public synchronized AvFrame step(InputFrame frame) throws IOException {
        Objects.requireNonNull(frame);
        var body = Wire.input(request(Wire.STEP, Wire.body(out -> {
            out.writeByte(frame.ports().size());
            for (PortInput port : frame.ports()) {
                out.writeShort(port.buttons()); out.writeShort(port.leftX()); out.writeShort(port.leftY());
                out.writeShort(port.rightX()); out.writeShort(port.rightY());
            }
        })));
        AvFrame result = Wire.frame(body); Wire.end(body); return result;
    }
    @Override public synchronized void reset() throws IOException { empty(Wire.RESET, new byte[0]); }
    @Override public synchronized byte[] saveState() throws IOException { return request(Wire.SAVE, new byte[0]); }
    @Override public synchronized void loadState(byte[] state) throws IOException { empty(Wire.LOAD, Objects.requireNonNull(state)); }
    @Override public synchronized byte[] readSram() throws IOException { return request(Wire.SRAM_READ, new byte[0]); }
    @Override public synchronized void writeSram(byte[] sram) throws IOException { empty(Wire.SRAM_WRITE, Objects.requireNonNull(sram)); }

    private void empty(int operation, byte[] body) throws IOException {
        if (request(operation, body).length != 0) throw new IOException("Unexpected worker reply");
    }
    private byte[] request(int operation, byte[] body) throws IOException {
        if (closed) throw new IOException("Emulator worker is closed");
        long request = ++nextRequest;
        Wire.write(output, operation, request, body);
        Wire.Packet response = Wire.read(input);
        if (response.operation() != (operation | 0x8000) || response.request() != request) throw new IOException("Worker reply does not match request");
        var reply = Wire.input(response.body());
        int status = reply.readUnsignedByte();
        if (status == 1) { String message = Wire.string(reply); Wire.end(reply); throw new IOException(message); }
        if (status != 0) throw new IOException("Invalid worker response status");
        return reply.readAllBytes();
    }

    @Override public synchronized void close() throws IOException {
        if (closed) {
            if (worker.isAlive()) throw new IOException("Owned emulator worker is still running: " + worker.pid());
            if (!nativeTempCleaned) { cleanNativeTemp(nativeTemp); nativeTempCleaned = true; }
            return;
        }
        IOException failure = null;
        try { empty(Wire.QUIT, new byte[0]); } catch (IOException ex) { failure = ex; }
        closed = true;
        try { socket.close(); } catch (IOException ex) { if (failure == null) failure = ex; }
        awaitExit(worker, 5000);
        if (worker.isAlive()) {
            forcedShutdown = true; worker.destroy(); awaitExit(worker, 2000);
            if (worker.isAlive()) { worker.destroyForcibly(); awaitExit(worker, 2000); }
            if (failure == null) failure = new IOException("Emulator worker did not stop normally");
        }
        if (!worker.isAlive()) {
            try { cleanNativeTemp(nativeTemp); nativeTempCleaned = true; }
            catch (IOException cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
            if (worker.exitValue() != 0 && failure == null) failure = new IOException("Emulator worker exited with code " + worker.exitValue());
        }
        if (failure != null) throw failure;
    }

    private static void requireFile(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException(label + " is missing: " + path);
    }
    private static void verifyNativeTemp(NativeTemp temp) throws IOException {
        Path path = temp.path.toAbsolutePath().normalize();
        if (!path.getParent().equals(temp.parent) || !path.getFileName().toString().startsWith("muxi-console-worker-") ||
                !temp.parent.toRealPath().equals(temp.parent)) throw new IOException("Native temporary path escaped its owned parent");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || (temp.fileKey != null && !Objects.equals(temp.fileKey, attributes.fileKey())))
            throw new IOException("Native temporary directory identity changed: " + path);
    }
    private static void cleanNativeTemp(NativeTemp temp) throws IOException {
        verifyNativeTemp(temp);
        if (!Files.exists(temp.path, LinkOption.NOFOLLOW_LINKS)) return;
        // JNA extracts flat files. Refuse unknown subdirectories rather than using
        // a recursive traversal that could cross a replaced directory/link.
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(temp.path)) {
            for (Path child : children) {
                Path absolute = child.toAbsolutePath().normalize();
                if (!absolute.getParent().equals(temp.path)) throw new IOException("Native temporary child escaped its owned directory");
                BasicFileAttributes attributes = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory() || attributes.isOther()) throw new IOException("Unexpected native temporary subdirectory or special file: " + absolute);
                entries.add(absolute);
            }
        }
        for (Path child : entries) { verifyNativeTemp(temp); Files.deleteIfExists(child); }
        verifyNativeTemp(temp); Files.delete(temp.path);
    }
    private static void awaitExit(Process process, int millis) {
        try { process.waitFor(millis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
