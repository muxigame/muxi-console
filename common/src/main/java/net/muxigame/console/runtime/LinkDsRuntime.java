package net.muxigame.console.runtime;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

/** Two independent DS processes advance concurrently and exchange real core NETPACKET traffic off the MC tick. */
public final class LinkDsRuntime implements AutoCloseable {
    public static final long MAX_GROUP_BYTES=320L*1024*1024+4096;
    public record Endpoint(UUID machine,String romSHA,LaunchConfig launch){
        public Endpoint{Objects.requireNonNull(machine);Objects.requireNonNull(launch);if(romSHA==null||!romSHA.matches("[a-f0-9]{64}")||!launch.platform().equals("nds"))throw new IllegalArgumentException("Invalid DS wireless endpoint");}
    }
    public record Config(String coreSHA,List<Endpoint> endpoints){
        public Config{endpoints=List.copyOf(endpoints);if(coreSHA==null||!coreSHA.matches("[a-f0-9]{64}")||endpoints.size()!=2||endpoints.get(0).machine.equals(endpoints.get(1).machine)||!endpoints.get(0).launch.core().toAbsolutePath().normalize().equals(endpoints.get(1).launch.core().toAbsolutePath().normalize()))throw new IllegalArgumentException("DS wireless needs two distinct machines and the same pinned native core");}
    }
    public record NetworkStats(long sentPackets,long receivedPackets,long sentBytes,long receivedBytes){}
    private final LinkDsNetwork network;
    private final Config config;
    private final EndpointWorker[] workers=new EndpointWorker[2];
    private final ExecutorService steps=Executors.newFixedThreadPool(2,task->{Thread t=new Thread(task,"muxi-ds-core-rpc");t.setDaemon(true);return t;});
    private boolean closed;private long nextFrame;
    private LinkDsRuntime(Config config)throws IOException{
        this.config=config;
        network=new LinkDsNetwork();
        try{
            for(Endpoint endpoint:config.endpoints)for(Path directory:List.of(endpoint.launch.systemDirectory(),endpoint.launch.saveDirectory())){Files.createDirectories(directory);if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||!directory.toAbsolutePath().normalize().equals(directory.toRealPath()))throw new IOException("Unsafe machine-specific DS profile directory");}
            for(Path a:List.of(config.endpoints.get(0).launch.systemDirectory(),config.endpoints.get(0).launch.saveDirectory()))for(Path b:List.of(config.endpoints.get(1).launch.systemDirectory(),config.endpoints.get(1).launch.saveDirectory())){Path left=a.toRealPath(),right=b.toRealPath();if(left.startsWith(right)||right.startsWith(left))throw new IOException("Wireless DS machines cannot share profile files");}
            for(int i=0;i<2;i++)workers[i]=EndpointWorker.open(config.endpoints.get(i),config.coreSHA,network.address(),i);
            network.awaitConnected(15000);
            if(!workers[0].protocol.equals(workers[1].protocol))throw new IOException("DS wireless core protocols differ");
        }catch(IOException|RuntimeException error){try{close();}catch(IOException cleanup){error.addSuppressed(cleanup);}throw error;}
    }
    public static LinkDsRuntime open(Config config)throws IOException{return new LinkDsRuntime(config);}
    private void active()throws IOException{if(closed)throw new IOException("DS wireless runtime closed");}
    public CoreInfo info(){return workers[0].info;}
    public synchronized LinkRuntime.Frame step(PortInput left,PortInput right)throws IOException{
        active();List<Future<AvFrame>> pending=new ArrayList<>();pending.add(steps.submit(()->workers[0].step(left)));pending.add(steps.submit(()->workers[1].step(right)));
        try{AvFrame a=pending.get(0).get(20,TimeUnit.SECONDS),b=pending.get(1).get(20,TimeUnit.SECONDS);if(a.number()!=b.number()||a.number()!=nextFrame)throw new IOException("DS wireless frame barriers differ");nextFrame++;return new LinkRuntime.Frame(a.number(),a,b);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("DS wireless step interrupted");}
        catch(ExecutionException failure){Throwable cause=failure.getCause();throw cause instanceof IOException io?io:new IOException("DS wireless worker failed",cause);}
        catch(TimeoutException timeout){throw new IOException("DS wireless frame barrier timed out");}
        finally{pending.forEach(task->task.cancel(true));}
    }
    private static void port(int port)throws IOException{if(port<0||port>1)throw new IOException("Invalid DS endpoint");}
    public synchronized NetworkStats networkStats(int port)throws IOException{active();port(port);return workers[port].stats();}
    /** Ordinary single-machine export only. This does not capture MpState queues or promise wireless group restoration. */
    public synchronized void exportEndpointTo(int port,Path target)throws IOException{active();port(port);workers[port].exportTo(target);}
    /** A single-machine state can only restore before that process executes any wireless frames. */
    public synchronized void restoreEndpointFrom(int port,Path source)throws IOException{active();port(port);workers[port].restoreFrom(source);}
    /** DNL1 is separate from GB/GBA LNK1 and ordinary MXS1; CPU, frontend and transport queues share one barrier. */
    public synchronized void saveLinkStateTo(Path target)throws IOException{
        saveLinkStateTo(target,false);
    }
    /** Optionally deliver queued bytes to the frontend while both CPUs remain paused; useful for a complete MP boundary. */
    public synchronized void saveLinkStateTo(Path target,boolean deliverPendingToFrontend)throws IOException{
        active();if(nextFrame==0)throw new IOException("DS group checkpoint needs a running wireless session");newTarget(target);List<Path> checkpoints=new ArrayList<>();boolean created=false,complete=false;
        try{
            // Both core threads are paused after step() returns. The ordered round
            // trip drains both data directions into incoming queues without entering native receive.
            workers[0].request(9,new byte[0]);for(EndpointWorker worker:workers)checkpoints.add(worker.fullSave(deliverPendingToFrontend));
            try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)))){
                created=true;out.writeInt(0x444e4c31);out.writeShort(1);out.writeUTF("melondsds");out.writeUTF(config.coreSHA);out.writeLong(nextFrame);
                for(int i=0;i<2;i++){Path file=checkpoints.get(i);Endpoint endpoint=config.endpoints.get(i);out.writeUTF(endpoint.machine.toString());out.writeUTF(endpoint.romSHA);out.writeUTF("nds");out.writeLong(Files.size(file));out.writeUTF(fileHash(file));Files.copy(file,out);}
            }
            if(Files.size(target)>MAX_GROUP_BYTES)throw new IOException("DS group checkpoint exceeds its independent capacity");complete=true;
        }finally{for(Path file:checkpoints)Files.deleteIfExists(file);if(created&&!complete)Files.deleteIfExists(target);}
    }
    /** Restore a complete pair into fresh workers only; partial native restore closes the pair and preserves disk checkpoints. */
    public synchronized void loadLinkStateFrom(Path source)throws IOException{
        active();if(nextFrame!=0)throw new IOException("DS group restore requires fresh unexecuted workers");EndpointWorker.ordinary(source);if(Files.size(source)>MAX_GROUP_BYTES)throw new IOException("DS group checkpoint exceeds capacity");List<Path> restored=new ArrayList<>();boolean applying=false;
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS)))){
            if(in.readInt()!=0x444e4c31||in.readUnsignedShort()!=1||!in.readUTF().equals("melondsds")||!in.readUTF().equals(config.coreSHA))throw new IOException("DS group backend or actual binary identity differs");long frame=in.readLong();if(frame<0)throw new IOException("Invalid DS group frame");
            for(int i=0;i<2;i++){
                Endpoint endpoint=config.endpoints.get(i);if(!in.readUTF().equals(endpoint.machine.toString())||!in.readUTF().equals(endpoint.romSHA)||!in.readUTF().equals("nds"))throw new IOException("DS group machine/content identity differs");long length=in.readLong();String expected=in.readUTF();if(length<16||length>LinkDsCheckpoint.MAX_FILE||!expected.matches("[a-f0-9]{64}"))throw new IOException("Invalid DS endpoint checkpoint bounds");Path file=workers[i].temp.resolve("endpoint-"+UUID.randomUUID()+".dse1");restored.add(file);try(var out=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)){LinkDsCheckpoint.copy(in,out,length);}if(!fileHash(file).equals(expected))throw new IOException("DS endpoint checkpoint checksum differs");
            }
            if(in.read()!=-1)throw new IOException("Trailing DS group bytes");applying=true;
            for(int i=0;i<2;i++)if(workers[i].fullRestore(restored.get(i))!=frame)throw new IOException("DS endpoint checkpoint frame barriers differ");nextFrame=frame;
        }catch(IOException|RuntimeException failure){if(applying)try{close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
        finally{for(Path file:restored)Files.deleteIfExists(file);}
    }
    private static void newTarget(Path file)throws IOException{if(!file.isAbsolute()||file.getParent()==null||!file.getParent().equals(file.getParent().toRealPath())||Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("DS checkpoint needs a new ordinary local path");}
    private static String fileHash(Path file)throws IOException{try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=new byte[65536];for(int n;(n=in.read(bytes))>=0;)if(n>0)digest.update(bytes,0,n);}return HexFormat.of().formatHex(digest.digest());}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    public List<Long> workerPids(){return Arrays.stream(workers).filter(Objects::nonNull).map(w->w.process.pid()).toList();}
    public boolean isAlive(){return Arrays.stream(workers).anyMatch(w->w!=null&&w.alive());}
    public boolean naturalExit(){return Arrays.stream(workers).filter(Objects::nonNull).allMatch(EndpointWorker::naturalExit);}
    @Override public synchronized void close()throws IOException{closed=true;IOException failure=null;for(EndpointWorker worker:workers)if(worker!=null)try{worker.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}steps.shutdownNow();try{network.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}if(failure!=null)throw failure;}

    private static final class EndpointWorker implements AutoCloseable {
        final Process process;final OwnedProcess owned;final Socket socket;final DataInputStream input;final DataOutputStream output;final Path temp,parent;final Object fileKey;final CoreInfo info;final String protocol;
        long sequence;boolean closed,forced,cleaned;
        private EndpointWorker(Process process,OwnedProcess owned,Socket socket,Path parent,Path temp,Object fileKey,Endpoint endpoint,String coreSHA,LinkDsNetwork.Address network,int port)throws IOException{
            this.process=process;this.owned=owned;this.socket=socket;this.parent=parent;this.temp=temp;this.fileKey=fileKey;input=new DataInputStream(new BufferedInputStream(socket.getInputStream()));output=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            byte[] result=request(LinkWire.OPEN,LinkWire.body(out->{LaunchConfig launch=endpoint.launch;LinkWire.text(out,launch.core().toAbsolutePath().toString());LinkWire.text(out,coreSHA);LinkWire.text(out,launch.content().toAbsolutePath().toString());LinkWire.text(out,endpoint.romSHA);LinkWire.text(out,endpoint.machine.toString());LinkWire.text(out,launch.systemDirectory().toAbsolutePath().toString());LinkWire.text(out,launch.saveDirectory().toAbsolutePath().toString());out.writeInt(network.port());LinkWire.text(out,network.token());out.writeByte(port);out.writeInt(launch.options().size());for(var entry:launch.options().entrySet()){LinkWire.text(out,entry.getKey());LinkWire.text(out,entry.getValue());}}));
            var body=LinkWire.input(result);String name=LinkWire.text(body),version=LinkWire.text(body);int width=body.readInt(),height=body.readInt();double fps=body.readDouble(),rate=body.readDouble();protocol=LinkWire.text(body);LinkWire.end(body);if(width!=256||height!=384||!Double.isFinite(fps)||fps<58||fps>61)throw new IOException("DS wireless core geometry or timing differs");info=new CoreInfo(name,version,width,height,fps,rate,2.0/3);
        }
        static EndpointWorker open(Endpoint endpoint,String hash,LinkDsNetwork.Address address,int port)throws IOException{
            OwnedProcess.prepare();Path parent=endpoint.launch.core().toAbsolutePath().getParent().toRealPath(),temp=Files.createTempDirectory(parent,"muxi-console-ds-link-");Object key=Files.readAttributes(temp,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();Process process=null;OwnedProcess owned=null;Socket socket=null;
            try(ServerSocket listener=new ServerSocket()){
                listener.bind(new InetSocketAddress("127.0.0.1",0),1);listener.setSoTimeout(250);byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);String token=HexFormat.of().formatHex(secret);String cp=String.join(File.pathSeparator,endpoint.launch.classpath().stream().map(p->p.toAbsolutePath().toString()).toList());
                process=new ProcessBuilder(endpoint.launch.javaExecutable().toAbsolutePath().toString(),"-Xms32m","-Xmx256m","-XX:ActiveProcessorCount=1","-Djna.tmpdir="+temp,"-cp",cp,LinkDsWorker.class.getName(),Integer.toString(listener.getLocalPort()),token).redirectErrorStream(true).start();owned=OwnedProcess.capture(process);process.getOutputStream().close();Process child=process;Thread drain=new Thread(()->{try(var logs=child.getInputStream()){logs.transferTo(OutputStream.nullOutputStream());}catch(IOException ignored){}},"muxi-ds-log-"+process.pid());drain.setDaemon(true);drain.start();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(socket==null){try{socket=listener.accept();}catch(SocketTimeoutException waiting){if(!owned.isAlive()||owned.reportedExitCode().isPresent())throw new IOException("DS worker began exiting before connecting");if(System.nanoTime()-end>=0)throw new IOException("DS worker startup timed out");}}
                socket.setTcpNoDelay(true);socket.setSoTimeout(20000);var auth=new DataInputStream(socket.getInputStream());if(!token.equals(LinkWire.text(auth)))throw new IOException("DS worker authentication failed");return new EndpointWorker(process,owned,socket,parent,temp,key,endpoint,hash,address,port);
            }catch(IOException|RuntimeException error){if(socket!=null)try{socket.close();}catch(IOException cleanup){error.addSuppressed(cleanup);}boolean terminated=process==null;
                if(owned!=null)try{terminated=owned.awaitExit(2000);if(!terminated){boolean forced=owned.terminate();terminated=owned.awaitExit(5000);if(forced)error.addSuppressed(new IOException("DS startup required termination of owned worker "+process.pid()));}}catch(IOException cleanup){error.addSuppressed(cleanup);}
                if(terminated)try{clean(parent,temp,key);}catch(IOException cleanup){error.addSuppressed(cleanup);}else error.addSuppressed(new IOException("DS worker termination unconfirmed; native files retained"));if(owned!=null)try{owned.close();}catch(IOException cleanup){error.addSuppressed(cleanup);}throw error;}
        }
        private synchronized byte[] request(int operation,byte[] bytes)throws IOException{if(closed)throw new IOException("DS worker closed");long id=sequence++;LinkWire.send(output,operation,id,bytes);LinkWire.Packet reply=LinkWire.receive(input);if(reply.sequence()!=id||reply.operation()!=(operation|0x8000))throw new IOException("DS worker response identity differs");var body=LinkWire.input(reply.body());int status=body.readUnsignedByte();if(status==1)throw new IOException(LinkWire.text(body));if(status!=0)throw new IOException("Invalid DS response status");return body.readAllBytes();}
        AvFrame step(PortInput state)throws IOException{var body=LinkWire.input(request(LinkWire.STEP,LinkWire.body(out->InputCodec.writePort(out,state))));AvFrame result=LinkWire.frame(body);LinkWire.end(body);return result;}
        NetworkStats stats()throws IOException{var body=LinkWire.input(request(8,new byte[0]));NetworkStats stats=new NetworkStats(body.readLong(),body.readLong(),body.readLong(),body.readLong());LinkWire.end(body);return stats;}
        Path fullSave(boolean deliver)throws IOException{var body=LinkWire.input(request(10,LinkWire.body(out->out.writeBoolean(deliver))));String name=LinkWire.text(body);LinkWire.end(body);if(!name.matches("endpoint-[a-f0-9-]{36}\\.dse1"))throw new IOException("Invalid DS group spool name");Path file=temp.resolve(name);ordinary(file);if(Files.size(file)>LinkDsCheckpoint.MAX_FILE)throw new IOException("DS endpoint group exceeds capacity");return file;}
        long fullRestore(Path source)throws IOException{if(!source.getParent().equals(temp)||!source.getFileName().toString().matches("endpoint-[a-f0-9-]{36}\\.dse1"))throw new IOException("DS restore file is outside its own worker spool");var body=LinkWire.input(request(11,LinkWire.body(out->LinkWire.text(out,source.getFileName().toString()))));long frame=body.readLong();LinkWire.end(body);return frame;}
        private static void ordinary(Path file)throws IOException{if(!file.isAbsolute()||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.equals(file.toRealPath())||Files.size(file)<16||Files.size(file)>512L*1024*1024)throw new IOException("Invalid DS single state file");}
        void exportTo(Path target)throws IOException{if(!target.isAbsolute()||target.getParent()==null||!target.getParent().equals(target.getParent().toRealPath())||Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("DS export requires a new ordinary local path");var body=LinkWire.input(request(LinkWire.EXPORT,new byte[0]));String name=LinkWire.text(body);LinkWire.end(body);if(!name.matches("endpoint-[a-f0-9-]{36}\\.mxs1"))throw new IOException("Invalid DS export spool");Path source=temp.resolve(name);try{ordinary(source);Files.copy(source,target);}finally{Files.deleteIfExists(source);}}
        void restoreFrom(Path source)throws IOException{ordinary(source);Path copy=temp.resolve("endpoint-"+UUID.randomUUID()+".mxs1");try{Files.copy(source,copy);request(LinkWire.RESTORE,LinkWire.body(out->LinkWire.text(out,copy.getFileName().toString())));}finally{Files.deleteIfExists(copy);}}
        boolean alive(){try{return owned.isAlive();}catch(IOException unconfirmed){return true;}}
        boolean naturalExit(){try{return owned.exitCode().orElse(-1)==0&&!forced&&cleaned;}catch(IOException unconfirmed){return false;}}
        @Override public void close()throws IOException{
            if(closed){if(owned.isAlive())throw new IOException("DS worker termination remains unconfirmed");if(!cleaned){clean(parent,temp,fileKey);cleaned=true;}owned.close();return;}
            IOException failure=null;try{request(LinkWire.QUIT,new byte[0]);}catch(IOException error){failure=error;}closed=true;try{socket.close();}catch(IOException error){if(failure==null)failure=error;}
            boolean terminated=false;try{terminated=owned.awaitExit(3000);if(!terminated){forced=owned.terminate();terminated=owned.awaitExit(5000);}}catch(IOException supervision){if(failure==null)failure=supervision;else failure.addSuppressed(supervision);}
            if(!terminated){IOException pending=new IOException("DS worker has not signaled termination; native files retained");if(failure!=null)pending.addSuppressed(failure);throw pending;}
            try{clean(parent,temp,fileKey);cleaned=true;}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}if(owned.exitCode().orElse(-1)!=0&&failure==null)failure=new IOException("DS worker exited abnormally");if(forced&&failure==null)failure=new IOException("DS worker required termination");try{owned.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}if(failure!=null)throw failure;
        }
    }
    private static void clean(Path parent,Path directory,Object key)throws IOException{if(!directory.getParent().equals(parent)||!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;var attributes=Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(!attributes.isDirectory()||attributes.isSymbolicLink()||key!=null&&!key.equals(attributes.fileKey()))throw new IOException("DS native directory identity changed");try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){if(Files.isSymbolicLink(path))throw new IOException("Unexpected DS native symbolic link");Files.delete(path);}}}
}
