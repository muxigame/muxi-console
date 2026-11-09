package net.muxigame.console.runtime;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Two independent 3DS CPUs in a fresh private Room. ENet queues are never advertised as checkpointed. */
public final class Link3dsRuntime implements AutoCloseable {
    public static final boolean supportsQueueCheckpoint=false;
    /** A startup outcome backed by owned process signals and native-directory cleanup. */
    public static final class StartupFailure extends IOException {
        private final boolean filesClosed;
        private StartupFailure(Throwable cause,boolean filesClosed){super(cause.getMessage(),cause);this.filesClosed=filesClosed;}
        public boolean filesClosed(){return filesClosed;}
    }
    /** Unknown exceptions have no cleanup proof. Stderr/suppressed diagnostics are never evidence. */
    public static boolean filesClosedAfterStartupFailure(Throwable failure){
        for(int depth=0;failure!=null&&depth<16;depth++,failure=failure.getCause())
            if(failure instanceof StartupFailure startup)return startup.filesClosed();
        return false;
    }
    public record Endpoint(UUID machine,String romSHA,LaunchConfig launch) {
        public Endpoint {Objects.requireNonNull(machine);Objects.requireNonNull(launch);if(romSHA==null||!romSHA.matches("[a-f0-9]{64}")||!launch.platform().equals("n3ds")||launch.content()==null)throw new IllegalArgumentException("Invalid 3DS endpoint");}
    }
    public record Config(String coreSHA,List<Endpoint> endpoints) {
        public Config {endpoints=List.copyOf(endpoints);if(coreSHA==null||!coreSHA.matches("[a-f0-9]{64}")||endpoints.size()!=2||endpoints.get(0).machine.equals(endpoints.get(1).machine)||!endpoints.get(0).launch.core().toAbsolutePath().normalize().equals(endpoints.get(1).launch.core().toAbsolutePath().normalize()))throw new IllegalArgumentException("3DS Room requires two machines and one exact native core");}
    }
    private final Config config;
    private final EndpointWorker[] workers=new EndpointWorker[2];
    private final ExecutorService steps=Executors.newFixedThreadPool(2,task->{Thread thread=new Thread(task,"playstation-3ds-rpc");thread.setDaemon(true);return thread;});
    private boolean closed,poisoned;private long frame;
    private Link3dsRuntime(Config config)throws IOException {
        this.config=config;
        try {
            validateDirectories(config.endpoints);
            for(int i=0;i<2;i++)workers[i]=EndpointWorker.open(config.endpoints.get(i),config.coreSHA);
            int roomPort;try(DatagramSocket reservation=new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0))){roomPort=reservation.getLocalPort();}
            String password=secret(16);workers[0].request(Link3dsWorker.HOST,address(roomPort,password));
            for(EndpointWorker worker:workers)worker.request(Link3dsWorker.JOIN,address(roomPort,password));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(!workers[0].status().joined()||!workers[1].status().joined()){
                if(System.nanoTime()-deadline>=0)throw new IOException("3DS private Room members did not join before startup deadline");
                try{Thread.sleep(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("3DS Room startup cancelled",interrupted);}
            }
            for(EndpointWorker worker:workers)worker.boot();
            if(Math.abs(workers[0].info.fps()-workers[1].info.fps())>.001)throw new IOException("3DS core timings differ");
        } catch(IOException|RuntimeException|LinkageError failure){
            boolean filesClosed=!(failure instanceof StartupFailure startup)||startup.filesClosed();
            try{close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            filesClosed&=Arrays.stream(workers).filter(Objects::nonNull).allMatch(worker->worker.cleaned&&worker.handleClosed);
            throw new StartupFailure(failure,filesClosed);
        }
    }
    static void validateDirectories(List<Endpoint> endpoints)throws IOException {
        for(Endpoint endpoint:endpoints)for(Path directory:List.of(endpoint.launch.systemDirectory(),endpoint.launch.saveDirectory())){
            Path absolute=directory.toAbsolutePath().normalize();Files.createDirectories(absolute);if(!Files.isDirectory(absolute,LinkOption.NOFOLLOW_LINKS)||!absolute.equals(absolute.toRealPath()))throw new IOException("3DS profiles require ordinary machine directories");
        }
        Path left=endpoints.get(0).launch.saveDirectory().toRealPath(),right=endpoints.get(1).launch.saveDirectory().toRealPath();
        if(overlap(left,right))throw new IOException("3DS machines cannot share writable native profiles");
        // System/firmware is read-only input and may be shared. It cannot alias
        // the other CPU's writable native filesystem in either direction.
        if(overlap(endpoints.get(0).launch.systemDirectory().toRealPath(),right)||overlap(endpoints.get(1).launch.systemDirectory().toRealPath(),left))
            throw new IOException("3DS firmware cannot overlap the partner's writable profile");
    }
    private static boolean overlap(Path a,Path b){return a.startsWith(b)||b.startsWith(a);}
    private static String secret(int bytes){byte[] secret=new byte[bytes];new SecureRandom().nextBytes(secret);return HexFormat.of().formatHex(secret);}
    private static byte[] address(int port,String password)throws IOException{return LinkWire.body(out->{out.writeInt(port);LinkWire.text(out,password);});}
    public static Link3dsRuntime open(Config config)throws IOException{return new Link3dsRuntime(config);}
    private void active()throws IOException {if(closed||poisoned)throw new IOException("3DS Room runtime closed or frame barrier failed");}
    private static void port(int port)throws IOException {if(port<0||port>1)throw new IOException("Invalid 3DS endpoint");}
    public CoreInfo info(){return workers[0].info;}
    public synchronized LinkRuntime.Frame step(PortInput left,PortInput right)throws IOException {
        active();Objects.requireNonNull(left);Objects.requireNonNull(right);List<Future<AvFrame>> pending=List.of(steps.submit(()->workers[0].step(left)),steps.submit(()->workers[1].step(right)));
        try {
            AvFrame a=pending.get(0).get(25,TimeUnit.SECONDS),b=pending.get(1).get(25,TimeUnit.SECONDS);
            if(a.number()!=frame||b.number()!=frame)throw new IOException("3DS CPU frame barriers differ");return new LinkRuntime.Frame(frame++,a,b);
        } catch(IOException failure){poisoned=true;throw failure;}
        catch(InterruptedException interrupted){poisoned=true;Thread.currentThread().interrupt();throw new IOException("3DS step cancelled",interrupted);}
        catch(TimeoutException timeout){poisoned=true;throw new IOException("3DS CPU frame deadline",timeout);}
        catch(ExecutionException failure){poisoned=true;throw failure.getCause() instanceof IOException io?io:new IOException("3DS worker step failed",failure.getCause());}
        finally{pending.forEach(task->task.cancel(true));}
    }
    public synchronized Link3dsNative.Status nativeStatus(int port)throws IOException {active();port(port);return workers[port].status();}
    public Path profileDirectory(int port)throws IOException {port(port);return config.endpoints.get(port).launch.saveDirectory();}
    /** Ordinary per-machine state only. The caller separately commits both native profiles and state metadata. */
    public synchronized void exportEndpointTo(int port,Path target)throws IOException {active();port(port);workers[port].exportTo(target);}
    /** Fresh Room + ordinary CPU restore; this explicitly does not restore lost ENet queues. */
    public synchronized void restoreEndpointFrom(int port,Path source)throws IOException {active();port(port);if(frame!=0)throw new IOException("3DS ordinary restore requires fresh CPUs");workers[port].restoreFrom(source);}
    public List<Long> workerPids(){return Arrays.stream(workers).filter(Objects::nonNull).map(worker->worker.process.pid()).toList();}
    public List<Path> workerNativeDirectories(){return Arrays.stream(workers).filter(Objects::nonNull).map(worker->worker.temp).toList();}
    public boolean isAlive(){return Arrays.stream(workers).filter(Objects::nonNull).anyMatch(EndpointWorker::alive);}
    public boolean naturalExit(){return Arrays.stream(workers).filter(Objects::nonNull).allMatch(worker->worker.natural);}
    @Override public synchronized void close()throws IOException {
        closed=true;IOException failure=null;for(EndpointWorker worker:workers)if(worker!=null)try{worker.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        steps.shutdownNow();if(failure!=null)throw failure;
    }
    private static final class EndpointWorker implements AutoCloseable {
        final Process process;final OwnedProcess owned;final Socket socket;final DataInputStream input;final DataOutputStream output;
        final Path parent,temp;final Object key;final StringBuilder tail;CoreInfo info;long sequence;int exit=-1;boolean closed,forced,cleaned,natural,handleClosed;
        EndpointWorker(Process process,OwnedProcess owned,Socket socket,Path parent,Path temp,Object key,StringBuilder tail,Endpoint endpoint,String coreSHA)throws IOException {
            this.process=process;this.owned=owned;this.socket=socket;this.parent=parent;this.temp=temp;this.key=key;this.tail=tail;
            input=new DataInputStream(new BufferedInputStream(socket.getInputStream()));output=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            var result=LinkWire.input(request(LinkWire.OPEN,LinkWire.body(out->{LaunchConfig launch=endpoint.launch;
                LinkWire.text(out,launch.core().toAbsolutePath().toString());LinkWire.text(out,coreSHA);LinkWire.text(out,launch.content().toAbsolutePath().toString());LinkWire.text(out,endpoint.romSHA);LinkWire.text(out,endpoint.machine.toString());
                LinkWire.text(out,launch.systemDirectory().toAbsolutePath().toString());LinkWire.text(out,launch.saveDirectory().toAbsolutePath().toString());out.writeInt(launch.options().size());for(var entry:launch.options().entrySet()){LinkWire.text(out,entry.getKey());LinkWire.text(out,entry.getValue());}})));
            Link3dsWorker.readStatus(result);LinkWire.end(result);
        }
        static EndpointWorker open(Endpoint endpoint,String coreSHA)throws IOException {
            try{return openOwned(endpoint,coreSHA);}
            catch(StartupFailure known){throw known;}
            catch(IOException|RuntimeException|LinkageError unknown){throw new StartupFailure(unknown,false);}
        }
        private static EndpointWorker openOwned(Endpoint endpoint,String coreSHA)throws IOException {
            OwnedProcess.prepare();Path parent=endpoint.launch.core().toAbsolutePath().getParent().toRealPath();Path temp=Files.createTempDirectory(parent,"muxi-console-3ds-link-");Object key=Files.readAttributes(temp,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
            Process process=null;OwnedProcess owned=null;Socket socket=null;StringBuilder tail=new StringBuilder();
            try(ServerSocket listener=new ServerSocket()) {
                listener.bind(new InetSocketAddress("127.0.0.1",0),1);listener.setSoTimeout(250);String token=secret(32);
                String classpath=String.join(File.pathSeparator,endpoint.launch.classpath().stream().map(path->path.toAbsolutePath().toString()).toList());
                process=new ProcessBuilder(endpoint.launch.javaExecutable().toAbsolutePath().toString(),"-Xms32m","-Xmx256m","-XX:ActiveProcessorCount=1","-Djna.tmpdir="+temp,"-Dorg.lwjgl.system.SharedLibraryExtractPath="+temp,"-cp",classpath,Link3dsWorker.class.getName(),Integer.toString(listener.getLocalPort()),token).redirectErrorStream(true).start();
                owned=OwnedProcess.capture(process);System.err.println("PlayStation 3DS worker started pid="+process.pid()+" created="+process.info().startInstant().orElse(null));process.getOutputStream().close();Process child=process;
                Thread logs=new Thread(()->{try(var input=new BufferedReader(new InputStreamReader(child.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))){for(String line;(line=input.readLine())!=null;){String safe=line.replace(token,"<redacted>");synchronized(tail){tail.append(safe).append('\n');if(tail.length()>8192)tail.delete(0,tail.length()-8192);}}}catch(IOException ignored){}},"playstation-3ds-log-"+process.pid());logs.setDaemon(true);logs.start();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(socket==null)try{socket=listener.accept();}catch(SocketTimeoutException waiting){if(!owned.isAlive()||owned.reportedExitCode().isPresent())throw new IOException("3DS worker began exiting before control connection");if(System.nanoTime()-deadline>=0)throw new IOException("3DS control connection deadline");}
                socket.setTcpNoDelay(true);socket.setSoTimeout(180000);var auth=new DataInputStream(socket.getInputStream());if(!token.equals(LinkWire.text(auth)))throw new IOException("3DS worker authentication failed");
                return new EndpointWorker(process,owned,socket,parent,temp,key,tail,endpoint,coreSHA);
            } catch(IOException|RuntimeException|LinkageError failure) {
                if(socket!=null)try{socket.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}boolean terminated=process==null;
                if(owned!=null)try{terminated=owned.awaitExit(15000);if(!terminated){boolean forced=owned.terminate();terminated=owned.awaitExit(5000);if(forced)failure.addSuppressed(new IOException("3DS startup required exact-owned worker termination pid="+process.pid()));}}catch(IOException cleanup){failure.addSuppressed(cleanup);}
                boolean cleaned=false;if(terminated)try{clean(parent,temp,key);cleaned=true;}catch(IOException cleanup){failure.addSuppressed(cleanup);}else failure.addSuppressed(new IOException("3DS process termination unconfirmed; owned native files retained"));
                if(owned!=null&&terminated)try{owned.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}synchronized(tail){if(!tail.isEmpty())failure.addSuppressed(new IOException("3DS startup diagnostic tail: "+tail));}throw new StartupFailure(failure,terminated&&cleaned);
            }
        }
        synchronized byte[] request(int operation,byte[] data)throws IOException {
            if(closed)throw new IOException("3DS worker closed");socket.setSoTimeout(operation==LinkWire.QUIT?15000:operation==LinkWire.STEP||operation==Link3dsWorker.STATUS?20000:180000);
            long id=sequence++;LinkWire.send(output,operation,id,data);LinkWire.Packet reply=LinkWire.receive(input);
            if(reply.sequence()!=id||reply.operation()!=(operation|0x8000))throw new IOException("3DS worker response identity differs");var body=LinkWire.input(reply.body());int status=body.readUnsignedByte();
            if(status==1)throw new IOException(LinkWire.text(body));if(status!=0)throw new IOException("Invalid 3DS response status");return body.readAllBytes();
        }
        void boot()throws IOException {
            var body=LinkWire.input(request(Link3dsWorker.BOOT,new byte[0]));info=new CoreInfo(LinkWire.text(body),LinkWire.text(body),body.readInt(),body.readInt(),body.readDouble(),body.readDouble(),body.readDouble());LinkWire.end(body);
            if(info.width()!=400||info.height()!=480||!Double.isFinite(info.fps())||info.fps()<58||info.fps()>61)throw new IOException("3DS native geometry or timing differs");
        }
        AvFrame step(PortInput state)throws IOException {var body=LinkWire.input(request(LinkWire.STEP,LinkWire.body(out->InputCodec.writePort(out,state))));AvFrame result=LinkWire.frame(body);LinkWire.end(body);return result;}
        Link3dsNative.Status status()throws IOException {var body=LinkWire.input(request(Link3dsWorker.STATUS,new byte[0]));var result=Link3dsWorker.readStatus(body);LinkWire.end(body);return result;}
        void exportTo(Path target)throws IOException {
            newTarget(target);var body=LinkWire.input(request(LinkWire.EXPORT,new byte[0]));String name=LinkWire.text(body);LinkWire.end(body);if(!name.matches("endpoint-[a-f0-9-]{36}\\.mxs1"))throw new IOException("Invalid 3DS export spool");Path source=temp.resolve(name);
            try{stateFile(source);Files.copy(source,target);}finally{Files.deleteIfExists(source);}
        }
        void restoreFrom(Path source)throws IOException {
            stateFile(source);Path copy=temp.resolve("endpoint-"+UUID.randomUUID()+".mxs1");try{Files.copy(source,copy);request(LinkWire.RESTORE,LinkWire.body(out->LinkWire.text(out,copy.getFileName().toString())));}finally{Files.deleteIfExists(copy);}
        }
        boolean alive(){if(handleClosed)return false;try{return owned.isAlive();}catch(IOException unknown){return true;}}
        @Override public void close()throws IOException {
            if(handleClosed){if(!cleaned){clean(parent,temp,key);cleaned=true;natural=exit==0&&!forced;}return;}IOException failure=null;
            if(!closed){try{request(LinkWire.QUIT,new byte[0]);}catch(IOException error){failure=error;}closed=true;try{socket.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}}
            boolean terminated=false;try{terminated=owned.awaitExit(15000);if(!terminated){forced|=owned.terminate();terminated=owned.awaitExit(5000);}}catch(IOException supervision){if(failure==null)failure=supervision;else failure.addSuppressed(supervision);}
            if(!terminated){IOException pending=new IOException("3DS worker has not signaled termination; exact-owned handle/native files retained");if(failure!=null)pending.addSuppressed(failure);throw pending;}
            exit=owned.exitCode().orElse(-1);try{clean(parent,temp,key);cleaned=true;}catch(IOException cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
            natural=exit==0&&!forced&&cleaned;if(!natural&&failure==null)failure=new IOException("3DS worker did not close naturally with native cleanup");
            System.err.println("PlayStation 3DS worker exit pid="+process.pid()+" code="+exit+" signaled="+terminated+" forced="+forced+" nativeClean="+cleaned);
            try{owned.close();handleClosed=true;}catch(IOException cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}if(failure!=null)throw failure;
        }
    }
    private static void newTarget(Path path)throws IOException {if(!path.isAbsolute()||path.getParent()==null||!path.getParent().equals(path.getParent().toRealPath())||Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("3DS export requires a new ordinary local path");}
    private static void stateFile(Path file)throws IOException {Link3dsWorker.ordinary(file);long size=Files.size(file);if(size<16||size>512L*1024*1024)throw new IOException("3DS ordinary state exceeds its bound");}
    private static void clean(Path parent,Path directory,Object key)throws IOException {
        if(!directory.getParent().equals(parent)||!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;
        var attributes=Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!attributes.isDirectory()||attributes.isSymbolicLink()||key!=null&&!key.equals(attributes.fileKey()))throw new IOException("Owned 3DS native directory identity changed");
        List<Path> files;try(var paths=Files.walk(directory,4)){files=paths.limit(257).toList();}if(files.size()>256||files.stream().anyMatch(Files::isSymbolicLink))throw new IOException("Unexpected files in owned 3DS native directory");
        for(Path file:files.stream().sorted(Comparator.reverseOrder()).toList())Files.delete(file);
    }
}
