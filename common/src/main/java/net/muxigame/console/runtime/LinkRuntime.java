package net.muxigame.console.runtime;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** One isolated worker executes two actual cable-linked CPUs; this Minecraft-facing class has no JNA reference. */
public final class LinkRuntime implements AutoCloseable {
    public record Endpoint(UUID machineId, String platform, String romSHA, Path content) {
        public Endpoint {
            Objects.requireNonNull(machineId); Objects.requireNonNull(content);
            if (!Set.of("gb","gbc","gba").contains(platform) || romSHA == null || !romSHA.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid cable endpoint identity");
        }
    }
    public record Config(Path javaExecutable, List<Path> classpath, Path core, String coreSHA,
                         Path systemDirectory, List<Endpoint> endpoints) {
        public Config {
            Objects.requireNonNull(javaExecutable); Objects.requireNonNull(core); Objects.requireNonNull(systemDirectory);
            classpath = List.copyOf(classpath); endpoints = List.copyOf(endpoints);
            if (classpath.isEmpty() || classpath.size() > 64 || coreSHA == null || !coreSHA.matches("[a-f0-9]{64}")
                    || endpoints.size() != 2 || endpoints.get(0).machineId().equals(endpoints.get(1).machineId()))
                throw new IllegalArgumentException("A link requires two different machines and a fixed bridge hash");
            if(endpoints.get(0).platform().equals("gba")!=endpoints.get(1).platform().equals("gba"))throw new IllegalArgumentException("GB and GBA use different cable hardware");
        }
    }
    public record Frame(long number, AvFrame left, AvFrame right) {
        public AvFrame endpoint(int index) { if (index == 0) return left; if (index == 1) return right; throw new IllegalArgumentException("Invalid link endpoint"); }
    }
    private final Process process;
    private final OwnedProcess owned;
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final Path nativeParent, nativeDirectory;
    private final Object nativeFileKey;
    private final CoreInfo info;
    private long sequence;
    private boolean closed, forcedShutdown, nativeTempCleaned;

    private LinkRuntime(Process process,OwnedProcess owned, Socket socket, DataInputStream input, DataOutputStream output,
                        Path nativeParent, Path nativeDirectory, Object nativeFileKey, Config config) throws IOException {
        this.process = process;this.owned=owned; this.socket = socket; this.input = input; this.output = output;
        this.nativeParent = nativeParent; this.nativeDirectory = nativeDirectory; this.nativeFileKey = nativeFileKey;
        byte[] reply = request(LinkWire.OPEN, LinkWire.body(out -> {
            LinkWire.text(out, config.core().toAbsolutePath().toString()); LinkWire.text(out, config.coreSHA());
            LinkWire.text(out, config.systemDirectory().toAbsolutePath().toString());
            for (Endpoint endpoint : config.endpoints()) {
                LinkWire.text(out, endpoint.machineId().toString()); LinkWire.text(out, endpoint.platform());
                LinkWire.text(out, endpoint.romSHA()); LinkWire.text(out, endpoint.content().toAbsolutePath().toString());
            }
        }));
        var data = LinkWire.input(reply);
        String name = LinkWire.text(data), version = LinkWire.text(data);int width=data.readInt(),height=data.readInt();double fps = data.readDouble(), rate = data.readDouble(); LinkWire.end(data);
        int expectedWidth=config.endpoints().get(0).platform().equals("gba")?240:160,expectedHeight=expectedWidth==240?160:144;
        if(width!=expectedWidth||height!=expectedHeight)throw new IOException("Link worker geometry differs from selected hardware");
        info = new CoreInfo(name, version, width, height, fps, rate, width/(double)height);
    }
    public static LinkRuntime open(Config config) throws IOException {
        OwnedProcess.prepare();
        regular(config.javaExecutable()); regular(config.core());
        for (Endpoint endpoint : config.endpoints()) regular(endpoint.content());
        Files.createDirectories(config.systemDirectory());
        if (Files.isSymbolicLink(config.systemDirectory()) || !Files.isDirectory(config.systemDirectory(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe link system directory");
        Path parent = config.core().toAbsolutePath().getParent().toRealPath();
        Path temp = Files.createTempDirectory(parent, "muxi-console-link-").toAbsolutePath();
        Object fileKey = Files.readAttributes(temp, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random); String token = HexFormat.of().formatHex(random);
        Process process = null;OwnedProcess owned=null; Socket socket = null;
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0), 1); listener.setSoTimeout(250);
            String cp = config.classpath().stream().map(p -> p.toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
            List<String> command = List.of(config.javaExecutable().toAbsolutePath().toString(), "-Xms32m", "-Xmx256m", "-XX:ActiveProcessorCount=1",
                    "-Djna.tmpdir=" + temp, "-cp", cp, "net.muxigame.console.runtime.LinkWorker", Integer.toString(listener.getLocalPort()), token);
            process = new ProcessBuilder(command).redirectErrorStream(true).start();owned=OwnedProcess.capture(process); process.getOutputStream().close();
            Process child = process;
            Thread drain = new Thread(() -> { try (var logs = child.getInputStream()) { logs.transferTo(OutputStream.nullOutputStream()); } catch (IOException ignored) { } }, "muxi-link-log-" + process.pid());
            drain.setDaemon(true); drain.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (socket == null) {
                try { socket = listener.accept(); }
                catch (SocketTimeoutException waiting) {
                    if (!owned.isAlive()||owned.reportedExitCode().isPresent()) throw new IOException("Link worker began exiting before connecting");
                    if (System.nanoTime() - deadline >= 0) throw new IOException("Link worker connection timed out");
                }
            }
            socket.setSoTimeout(15000); socket.setTcpNoDelay(true);
            var in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            if (!token.equals(LinkWire.text(in))) throw new IOException("Link worker authentication failed");
            return new LinkRuntime(process,owned, socket, in, out, parent, temp, fileKey, config);
        } catch (IOException | RuntimeException error) {
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
            boolean terminated=process==null;
            if(owned!=null)try{terminated=owned.awaitExit(2000);if(!terminated){boolean forced=owned.terminate();terminated=owned.awaitExit(5000);if(forced)error.addSuppressed(new IOException("Link startup required termination of owned worker "+process.pid()));}}catch(IOException cleanup){error.addSuppressed(cleanup);}
            if(terminated)try{clean(parent,temp,fileKey);}catch(IOException cleanup){error.addSuppressed(cleanup);}else error.addSuppressed(new IOException("Link worker termination unconfirmed; native directory retained"));
            if(owned!=null)try{owned.close();}catch(IOException cleanup){error.addSuppressed(cleanup);}
            throw error;
        }
    }
    public CoreInfo info() { return info; }
    public synchronized Frame step(PortInput left, PortInput right) throws IOException {
        byte[] reply = request(LinkWire.STEP, LinkWire.body(out -> { InputCodec.writePort(out, left); InputCodec.writePort(out, right); }));
        var data = LinkWire.input(reply); AvFrame first = LinkWire.frame(data), second = LinkWire.frame(data); LinkWire.end(data);
        if (first.number() != second.number()) throw new IOException("Link endpoint frame numbers differ");
        return new Frame(first.number(), first, second);
    }
    /** Standard single-machine MXS1 bytes, export-format-compatible only with the separately proven base core. */
    public synchronized byte[] exportEndpoint(int endpoint) throws IOException {
        index(endpoint); return request(LinkWire.EXPORT, LinkWire.body(out -> out.writeByte(endpoint)));
    }
    public synchronized void restoreEndpoint(int endpoint, byte[] singleWorkerState) throws IOException {
        index(endpoint); empty(LinkWire.RESTORE, LinkWire.body(out -> { out.writeByte(endpoint); LinkWire.bytes(out, singleWorkerState); }));
    }
    /** LNK1 binds both machine/ROM identities and the actual bridge hash; never put it in a single-machine vault. */
    public synchronized byte[] saveLinkState() throws IOException { return request(LinkWire.SAVE_LINK, new byte[0]); }
    public synchronized void loadLinkState(byte[] state) throws IOException { empty(LinkWire.LOAD_LINK, state); }
    private void empty(int operation, byte[] body) throws IOException { if (request(operation, body).length != 0) throw new IOException("Unexpected link worker response"); }
    private byte[] request(int operation, byte[] body) throws IOException {
        if (closed) throw new IOException("Link runtime is closed"); long id = sequence++;
        LinkWire.send(output, operation, id, body); LinkWire.Packet packet = LinkWire.receive(input);
        if (packet.operation() != (operation | 0x8000) || packet.sequence() != id) throw new IOException("Link response identity differs");
        var data = LinkWire.input(packet.body()); int status = data.readUnsignedByte();
        if (status == 1) throw new IOException(LinkWire.text(data));
        if (status != 0) throw new IOException("Invalid link response status"); return data.readAllBytes();
    }
    public long workerPid() { return process.pid(); }
    public boolean isAlive() {try{return owned.isAlive();}catch(IOException unconfirmed){return true;}}
    public OptionalInt exitCode() {try{return owned.exitCode();}catch(IOException unconfirmed){return OptionalInt.empty();}}
    public boolean nativeTempCleaned() { return nativeTempCleaned; }
    public boolean wasForcedShutdown() { return forcedShutdown; }
    @Override public synchronized void close() throws IOException {
        if(closed){if(owned.isAlive())throw new IOException("Owned link worker termination remains unconfirmed");if(!nativeTempCleaned){clean(nativeParent,nativeDirectory,nativeFileKey);nativeTempCleaned=true;}owned.close();return;} IOException failure = null;
        try { empty(LinkWire.QUIT, new byte[0]); } catch (IOException error) { failure = error; }
        closed = true; try { socket.close(); } catch (IOException error) { if (failure == null) failure = error; }
        boolean terminated=false;try{terminated=owned.awaitExit(3000);if(!terminated){forcedShutdown=owned.terminate();terminated=owned.awaitExit(5000);}}catch(IOException supervision){if(failure==null)failure=supervision;else failure.addSuppressed(supervision);}
        if(!terminated){IOException pending=new IOException("Owned link worker has not signaled termination; native directory retained");if(failure!=null)pending.addSuppressed(failure);throw pending;}
        try { clean(nativeParent, nativeDirectory, nativeFileKey); nativeTempCleaned = true; }
        catch (IOException error) { if (failure == null) failure = error; }
        OptionalInt code=owned.exitCode();if((code.isEmpty()||code.getAsInt()!=0)&&failure==null)failure=new IOException("Link worker exited abnormally");
        if (forcedShutdown && failure == null) failure = new IOException("Link worker required termination");
        try{owned.close();}catch(IOException cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
        if (failure != null) throw failure;
    }
    private static void regular(Path path) throws IOException { if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected an ordinary link file"); }
    private static void index(int endpoint) { if (endpoint < 0 || endpoint > 1) throw new IllegalArgumentException("Invalid link endpoint"); }
    private static void clean(Path parent, Path directory, Object fileKey) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        BasicFileAttributes attrs = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!directory.getParent().toRealPath().equals(parent) || !directory.getFileName().toString().startsWith("muxi-console-link-")
                || !directory.toRealPath().equals(directory.toAbsolutePath().normalize())
                || attrs.isSymbolicLink() || !attrs.isDirectory() || fileKey != null && !fileKey.equals(attrs.fileKey()))
            throw new IOException("Link native directory ownership differs");
        try (var files = Files.walk(directory)) {
            List<Path> owned = files.sorted(Comparator.reverseOrder()).toList();
            for (Path path : owned)
                if (!path.toAbsolutePath().normalize().startsWith(directory) || Files.isSymbolicLink(path)
                        || !path.toRealPath().startsWith(directory.toRealPath())) throw new IOException("Unsafe link native cleanup entry");
            for (Path path : owned) Files.delete(path);
        }
    }
}
