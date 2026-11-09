import net.muxigame.console.runtime.*;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** Independently boots only our original diagnostics in the real isolated worker. */
public final class ExpansionProbe {
    public static void main(String[] args)throws Exception{
        Path java=Path.of(args[0]),core=Path.of(args[2]),rom=Path.of(args[3]),out=Path.of(args[4]);
        Files.createDirectories(out);
        Map<String,String> options=new LinkedHashMap<>();
        for(int i=6;i<args.length;i++){int at=args[i].indexOf('=');options.put(args[i].substring(0,at),args[i].substring(at+1));}
        List<Path> cp=Arrays.stream(args[1].split(File.pathSeparator)).map(Path::of).toList();
        ProcessConsoleRuntime runtime=null;long started=System.nanoTime();
        try{
            runtime=ProcessConsoleRuntime.open(new LaunchConfig(java,cp,core,rom,out.resolve("system"),out.resolve("save"),options,args[5]));
            System.out.println("INFO "+runtime.info()+" pid="+runtime.workerPid());
            System.out.flush();
            for(int i=0;i<180;i++){
                PortInput pad=new PortInput(i>=90?1<<8:0);
                if(i>=120)pad=new PortInput(pad.buttons(),(short)0,(short)0,(short)0,(short)0,args[5].equals("wii")?769:1,new PortInput.Pointer(args[5].equals("wii")?"primary":"bottom",(short)-10000,(short)10000,true,true),new PortInput.Motion(0.25f,0.5f,-0.9f,0.1f,0.2f,0.3f,System.nanoTime(),true),new PortInput.Microphone(true,48000,new short[800]));
                AvFrame frame=runtime.step(new InputFrame(List.of(pad)));
                if(i==0||i==89||i==179){
                    BufferedImage picture=new BufferedImage(frame.width(),frame.height(),BufferedImage.TYPE_INT_ARGB);
                    byte[] rgba=frame.rgba();
                    for(int p=0;p<frame.width()*frame.height();p++)picture.setRGB(p%frame.width(),p/frame.width(),((rgba[p*4+3]&255)<<24)|((rgba[p*4]&255)<<16)|((rgba[p*4+1]&255)<<8)|(rgba[p*4+2]&255));
                    ImageIO.write(picture,"png",out.resolve("frame-"+i+".png").toFile());
                    System.out.println("FRAME "+i+" "+frame.width()+"x"+frame.height()+" samples="+frame.stereoPcm().length+" shutdown="+frame.shutdown()+" surfaces="+frame.surfaces());System.out.flush();
                }
            }
            System.out.println("STATE_SIZE "+runtime.stateSize());System.out.flush();
            if(runtime.stateSize()>0&&runtime.stateSize()<16*1024*1024){byte[] saved=runtime.saveState();Files.write(out.resolve("actual-state.mxs1"),saved);runtime.step(InputFrame.idle());runtime.loadState(saved);System.out.println("STATE_ROUNDTRIP "+saved.length);}
            Path spool=out.resolve("stream-state.mxs1");runtime.saveStateTo(spool);runtime.step(InputFrame.idle());runtime.loadStateFrom(spool);System.out.println("STREAM_ROUNDTRIP "+Files.size(spool));
        }finally{
            if(runtime!=null){try{runtime.close();}finally{System.out.println("CLOSE alive="+runtime.isAlive()+" forced="+runtime.wasForcedShutdown()+" exit="+runtime.exitCode()+" nativeClean="+runtime.nativeTemporaryFilesCleaned());}}
            try(var paths=Files.walk(out.resolve("save"))){for(Path p:paths.filter(Files::isRegularFile).toList())System.out.println("FILE "+out.resolve("save").relativize(p)+" "+Files.size(p));}
            System.out.println("ELAPSED_MS "+(System.nanoTime()-started)/1_000_000);
        }
    }
}
