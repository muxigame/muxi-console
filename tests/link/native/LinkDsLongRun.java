package net.muxigame.console.tests.link;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import net.muxigame.console.runtime.*;

/** Offline endurance of the exact shipped worker, with immutable background reads after a real checkpoint. */
public final class LinkDsLongRun {
    private static String sha(Path path)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] bytes=new byte[65536];for(int n;(n=in.read(bytes))>=0;)if(n>0)digest.update(bytes,0,n);}return HexFormat.of().formatHex(digest.digest());
    }
    private static int counter(AvFrame frame){byte[] rgba=frame.rgba();int value=0;for(int bit=0;bit<8;bit++){int at=(36*256+36+bit*8)*4;if((rgba[at+1]&255)>200&&(rgba[at]&255)<32)value|=1<<bit;}return value;}
    public static void main(String[] args)throws Exception{
        if(args.length!=7)throw new IllegalArgumentException("java core exactSHA left right output frames");
        Path output=Path.of(args[5]);Files.createDirectories(output);int frames=Integer.parseInt(args[6]);
        List<Path> cp=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).map(Path::of).toList();List<LinkDsRuntime.Endpoint> endpoints=new ArrayList<>();
        for(int i=0;i<2;i++){UUID machine=new UUID(0,i+1);Path rom=Path.of(args[3+i]),save=Files.createDirectories(output.resolve("machine-"+i)),system=Files.createDirectories(save.resolve("system"));endpoints.add(new LinkDsRuntime.Endpoint(machine,sha(rom),new LaunchConfig(Path.of(args[0]),cp,Path.of(args[1]),rom,system,save,LinkDsIdentity.singleOptions(machine),"nds")));}
        LinkDsRuntime runtime=null;List<Long> pids=List.of();Throwable failure=null;int last=-1;long captureNanos=0,captureBytes=0,backgroundStartFrame=-1,backgroundEndFrame=-1,start=System.nanoTime();CompletableFuture<Long> background=null;ExecutorService io=Executors.newSingleThreadExecutor();
        try{
            runtime=LinkDsRuntime.open(new LinkDsRuntime.Config(args[2],endpoints));pids=runtime.workerPids();System.out.println("workers="+pids);long scheduled=System.nanoTime();
            for(int index=0;index<frames;index++){
                int phase=index%900;PortInput left=new PortInput(phase>=120&&phase<126?256:0),right=new PortInput(phase>=240&&phase<246?256:0);
                LinkRuntime.Frame frame=runtime.step(left,right);last=index;
                if(index==3600){Path checkpoint=output.resolve("frame-3601.dnl1");long before=System.nanoTime();runtime.saveLinkStateTo(checkpoint);captureNanos=System.nanoTime()-before;captureBytes=Files.size(checkpoint);backgroundStartFrame=index;
                    background=CompletableFuture.supplyAsync(()->{try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12),read=0;byte[] bytes=new byte[24576];try(var in=Files.newInputStream(checkpoint)){for(int n;(n=in.read(bytes))>=0;)if(n>0){read+=n;long wait=deadline-(TimeUnit.SECONDS.toNanos(12)*(Files.size(checkpoint)-read)/Files.size(checkpoint))-System.nanoTime();if(wait>0)TimeUnit.NANOSECONDS.sleep(wait);}}return read;}catch(Exception error){throw new CompletionException(error);}},io);
                }
                if(background!=null&&background.isDone()&&backgroundEndFrame<0){if(background.get()!=captureBytes)throw new IOException("Immutable checkpoint read differs");backgroundEndFrame=index;}
                if(index%600==0){System.out.println("frame="+index+" counters="+counter(frame.left())+","+counter(frame.right()));Files.writeString(output.resolve("progress.json"),"{\"frame\":"+index+",\"workers\":"+pids+"}");}
                scheduled+=16_666_667L;long delay=scheduled-System.nanoTime();if(delay>0)TimeUnit.NANOSECONDS.sleep(delay);else if(delay< -TimeUnit.MILLISECONDS.toNanos(100))scheduled=System.nanoTime();
            }
            if(background==null||background.get()!=captureBytes)throw new IOException("Background read not complete");
            for(int i=0;i<2;i++)runtime.exportEndpointTo(i,output.resolve("final-"+i+".mxs1"));
        }catch(Throwable error){failure=error;}
        finally{io.shutdownNow();if(runtime!=null)try{runtime.close();}catch(Throwable cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}}
        boolean natural=runtime!=null&&runtime.naturalExit();String type=failure==null?"":failure.getClass().getSimpleName();String report="{\"success\":"+(failure==null&&natural&&last==frames-1)+",\"framesCompleted\":"+(last+1)+",\"targetFrames\":"+frames+",\"workers\":"+pids+",\"naturalExit\":"+natural+",\"captureNanos\":"+captureNanos+",\"checkpointBytes\":"+captureBytes+",\"backgroundStartFrame\":"+backgroundStartFrame+",\"backgroundEndFrame\":"+backgroundEndFrame+",\"elapsedNanos\":"+(System.nanoTime()-start)+",\"failureType\":\""+type+"\"}";
        Files.writeString(output.resolve("result.json"),report);System.out.println(report);if(failure!=null)throw new IOException("Offline DS endurance failed",failure);if(!natural)throw new IOException("Offline workers did not close naturally");
    }
}
