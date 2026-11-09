package net.muxigame.console.runtime;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

/** Minecraft-facing IPC; native calls in this process are limited to OS process supervision. */
public final class ProcessConsoleRuntime implements ConsoleRuntime {
    private final Process worker;
    private final OwnedProcess owned;
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
        OwnedProcess.prepare();
        requireFile(config.javaExecutable(), "Java executable"); requireFile(config.core(), "Emulator core");
        if (config.content() != null) requireFile(config.content(), "Game content");
        Files.createDirectories(config.systemDirectory()); Files.createDirectories(config.saveDirectory());
        Path parent = config.core().toAbsolutePath().normalize().getParent().toRealPath();
        Path path = Files.createTempDirectory(parent, "muxi-console-worker-").toAbsolutePath().normalize();
        NativeTemp temp = new NativeTemp(parent, path, Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey());
        Process process = null; OwnedProcess owned = null; Socket connection = null; StartupDiagnostics diagnostics = null;
        byte[] tokenBytes = new byte[32]; new SecureRandom().nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
            server.setSoTimeout(250);
            String classpath = config.classpath().stream().map(p -> p.toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
            List<String> command = List.of(config.javaExecutable().toAbsolutePath().toString(), "-Xms32m", "-Xmx256m",
                    "-XX:ActiveProcessorCount=1", "-Djna.tmpdir=" + temp.path,
                    "-Dorg.lwjgl.system.SharedLibraryExtractPath=" + temp.path, "-cp", classpath,
                    "net.muxigame.console.runtime.WorkerMain", Integer.toString(server.getLocalPort()), token);
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            diagnostics = new StartupDiagnostics(process.getInputStream(),token,process.pid());
            owned = OwnedProcess.capture(process);
            process.getOutputStream().close();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while(connection==null){
                try{connection=server.accept();}
                catch(SocketTimeoutException waiting){
                    if(!owned.isAlive()||owned.reportedExitCode().isPresent())throw new IOException("Worker began exiting before connecting");
                    if(System.nanoTime()-deadline>=0)throw new IOException("Worker did not connect within 15 seconds",waiting);
                }
            }
            connection.setSoTimeout(15000); connection.setTcpNoDelay(true);
            var in = new DataInputStream(new BufferedInputStream(connection.getInputStream()));
            var out = new DataOutputStream(new BufferedOutputStream(connection.getOutputStream()));
            if (!token.equals(Wire.string(in))) throw new IOException("Worker authentication failed");
            return new ProcessConsoleRuntime(process, owned, connection, in, out, config, temp);
        } catch (IOException | RuntimeException ex) {
            if (connection != null) try { connection.close(); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            boolean terminated = process == null, forced = false;
            if (owned != null) {
                // EOF lets a responsive worker unload and exit before any termination.
                try {
                    terminated=owned.awaitExit(2000);
                    if(!terminated){forced=owned.terminate();terminated=owned.awaitExit(5000);if(forced)ex.addSuppressed(new IOException("Startup required termination of owned worker "+process.pid()));}
                } catch(IOException cleanup) { ex.addSuppressed(cleanup); }
            }
            if (terminated) try { cleanNativeTemp(temp); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            else ex.addSuppressed(new IOException("Owned worker still running; native temporary directory retained: " + process.pid()));
            String exit="still running";
            if(owned!=null)try{OptionalInt code=owned.exitCode();if(code.isPresent())exit=Integer.toString(code.getAsInt());}catch(IOException cleanup){ex.addSuppressed(cleanup);}
            if(owned!=null)try{owned.close();}catch(IOException cleanup){ex.addSuppressed(cleanup);}
            if(process!=null){String detail=diagnostics==null?"":diagnostics.summary();throw new IOException("Worker startup failed [exit="+exit+", forced="+forced+", "+ex.getClass().getSimpleName()+"]: "+StartupDiagnostics.safe(String.valueOf(ex.getMessage()),token)+(detail.isEmpty()?"":"; stderr="+detail),ex);}
            throw ex;
        }
    }

    /** Drain continuously to avoid pipe deadlock; retain only a fixed prefix in memory. */
    private static final class StartupDiagnostics implements Runnable {
        private static final int LIMIT=8192;
        private final InputStream input;private final String token;private final byte[] captured=new byte[LIMIT];private int count;
        private final Thread thread;
        StartupDiagnostics(InputStream input,String token,long pid){this.input=input;this.token=token;thread=new Thread(this,"muxi-console-worker-diagnostics-"+pid);thread.setDaemon(true);thread.start();}
        public void run(){try(input){byte[] buffer=new byte[2048];for(int n;(n=input.read(buffer))!=-1;){synchronized(this){int kept=Math.min(n,LIMIT-count);if(kept>0){System.arraycopy(buffer,0,captured,count,kept);count+=kept;}}}}catch(IOException closed){}finally{if(Boolean.getBoolean("muxi.console.workerDiagnostics")){String text=snapshot();if(!text.isEmpty())System.err.println(text);}}}
        private synchronized String snapshot(){return safe(new String(captured,0,count,StandardCharsets.UTF_8),token);}
        String summary(){try{thread.join(250);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}return snapshot();}
        static String safe(String text,String token){return text.replace(token,"<token>").replaceAll("(?i)https?://[^\\s]+","<remote>").replaceAll("(?i)(token|secret|password|authorization|signature|credential)=[^\\s;]+","$1=<redacted>").replaceAll("[A-Za-z]:[\\\\/][^\\r\\n;]*","<local-file>").replaceAll("(?<![A-Za-z0-9])/(?:[^\\s/:]+/)+[^\\s:]*","<local-file>");}
    }

    private ProcessConsoleRuntime(Process worker, OwnedProcess owned, Socket socket, DataInputStream input, DataOutputStream output, LaunchConfig config, NativeTemp nativeTemp) throws IOException {
        this.worker = worker; this.owned = owned; this.socket = socket; this.input = input; this.output = output;
        this.nativeTemp = nativeTemp;
        byte[] result = request(Wire.OPEN, Wire.body(out -> {
            Wire.string(out, config.core().toAbsolutePath().toString());
            Wire.string(out, config.content() == null ? "" : config.content().toAbsolutePath().toString());
            Wire.string(out, config.systemDirectory().toAbsolutePath().toString());
            Wire.string(out, config.saveDirectory().toAbsolutePath().toString());
            out.writeInt(config.options().size());
            for (var entry : config.options().entrySet()) { Wire.string(out, entry.getKey()); Wire.string(out, entry.getValue()); }
            Wire.string(out,config.platform());
        }));
        var body = Wire.input(result); info = Wire.info(body); Wire.end(body);
    }

    @Override public CoreInfo info() { return info; }
    @Override public synchronized DiskState diskState() throws IOException {
        var body = Wire.input(request(Wire.DISK_STATE, new byte[0]));
        try { DiskState state = new DiskState(body.readInt(), body.readInt(), body.readBoolean()); Wire.end(body); return state; }
        catch (IllegalArgumentException ex) { throw new IOException("Invalid worker disk state", ex); }
    }
    @Override public synchronized void selectDisk(int index) throws IOException {
        if (index < 0 || index >= 256) throw new IOException("Invalid disk index");
        request(Wire.DISK_SELECT, Wire.body(out -> out.writeInt(index)));
    }
    @Override public synchronized void setDiskEjected(boolean ejected) throws IOException {
        empty(Wire.DISK_EJECT, Wire.body(out -> out.writeByte(ejected ? 1 : 0)));
    }
    public long workerPid() { return worker.pid(); }
    public boolean isAlive() { try{return owned.isAlive();}catch(IOException unknown){return true;} }
    public OptionalInt exitCode() { try{return owned.exitCode();}catch(IOException unknown){return OptionalInt.empty();} }
    public boolean wasForcedShutdown() { return forcedShutdown; }
    public Path nativeTemporaryDirectory() { return nativeTemp.path; }
    public boolean nativeTemporaryFilesCleaned() { return nativeTempCleaned; }

    @Override public synchronized AvFrame step(InputFrame frame) throws IOException {
        Objects.requireNonNull(frame);
        var body = Wire.input(request(Wire.STEP, Wire.body(out -> Wire.bytes(out,InputCodec.encodeFrame(frame)))));
        AvFrame result = Wire.frame(body); Wire.end(body); return result;
    }
    @Override public synchronized void reset() throws IOException { empty(Wire.RESET, new byte[0]); }
    @Override public synchronized byte[] saveState() throws IOException { return request(Wire.SAVE, new byte[0]); }
    public synchronized long stateSize()throws IOException{var body=Wire.input(request(Wire.STATE_SIZE,new byte[0]));long size=body.readLong();Wire.end(body);return size;}
    @Override public synchronized void loadState(byte[] state) throws IOException { empty(Wire.LOAD, Objects.requireNonNull(state)); }
    @Override public synchronized void saveStateTo(Path path)throws IOException {empty(Wire.SAVE_TO,Wire.body(out->Wire.string(out,path.toAbsolutePath().normalize().toString())));}
    @Override public synchronized void loadStateFrom(Path path)throws IOException {empty(Wire.LOAD_FROM,Wire.body(out->Wire.string(out,path.toAbsolutePath().normalize().toString())));}
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
            if (owned.isAlive()) throw new IOException("Owned emulator worker is still running: " + worker.pid());
            if (!nativeTempCleaned) { cleanNativeTemp(nativeTemp); nativeTempCleaned = true; }
            owned.close();
            return;
        }
        IOException failure = null;
        try { empty(Wire.QUIT, new byte[0]); } catch (IOException ex) { failure = ex; }
        closed = true;
        try { socket.close(); } catch (IOException ex) { if (failure == null) failure = ex; }
        boolean terminated=false;
        try{terminated=owned.awaitExit(5000);if(!terminated){forcedShutdown=owned.terminate();terminated=owned.awaitExit(5000);if(forcedShutdown&&failure==null)failure=new IOException("Emulator worker required explicit termination");}}
        catch(IOException supervision){if(failure==null)failure=supervision;else failure.addSuppressed(supervision);}
        if (terminated) {
            try { cleanNativeTemp(nativeTemp); nativeTempCleaned = true; }
            catch (IOException cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
            try{OptionalInt code=owned.exitCode();if(code.isEmpty()||code.getAsInt()!=0){IOException abnormal=new IOException("Emulator worker exited with code "+(code.isPresent()?code.getAsInt():"unconfirmed"));if(failure==null)failure=abnormal;else failure.addSuppressed(abnormal);}}
            catch(IOException supervision){if(failure==null)failure=supervision;else failure.addSuppressed(supervision);}
            try{owned.close();}catch(IOException cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
        }
        else if(failure==null)failure=new IOException("Owned emulator worker has not terminated; native temporary directory retained");
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
}
