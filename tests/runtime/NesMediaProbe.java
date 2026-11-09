package net.muxigame.console.runtime;

import java.nio.file.*;
import java.util.*;

/** Original two-controller diagnostic with a real core-generated pulse for media transport QA. */
public final class NesMediaProbe {
    private static int checks;
    private static long nonzero;
    private static AvFrame run(ConsoleRuntime runtime,InputFrame input,int count)throws Exception{
        AvFrame frame=null;
        for(int i=0;i<count;i++){
            frame=runtime.step(input);
            for(short sample:frame.stereoPcm())if(sample!=0)nonzero++;
        }
        return frame;
    }
    public static void main(String[] args)throws Exception{
        Path javaExecutable=Path.of(args[0]),core=Path.of(args[2]),rom=Path.of(args[3]),out=Path.of(args[4]);
        List<Path> cp=Arrays.stream(args[1].split(java.io.File.pathSeparator)).map(Path::of).toList();
        ProcessConsoleRuntime runtime=ProcessConsoleRuntime.open(new LaunchConfig(javaExecutable,cp,core,rom,out.resolve("system"),out.resolve("save")));
        long pid=runtime.workerPid();
        try{
            AvFrame baseline=run(runtime,InputFrame.idle(),90);
            check(nonzero>1000,"actual core PCM contains the original pulse");
            check(baseline.width()==256&&baseline.height()>200,"actual NES display dimensions");
            byte[] snapshot=runtime.saveState();
            AvFrame p1=run(runtime,new InputFrame(List.of(new PortInput(1<<8))),6);
            check(!pixel(baseline,p1,1)&&pixel(baseline,p1,3),"P1 changes only its left region");
            check(runtime.readSram()[0]==1&&runtime.readSram()[1]!=2,"P1 has its own real battery marker");
            runtime.loadState(snapshot);
            AvFrame p2=run(runtime,new InputFrame(List.of(PortInput.IDLE,new PortInput(1<<8))),6);
            check(pixel(baseline,p2,1)&&!pixel(baseline,p2,3),"P2 changes only its right region");
            check(runtime.readSram()[0]!=1&&runtime.readSram()[1]==2,"P2 has its own real battery marker");
            runtime.loadState(snapshot);AvFrame restored=run(runtime,InputFrame.idle(),6);
            check(pixel(baseline,restored,1)&&pixel(baseline,restored,3),"ordinary state restores both visual regions");
            check(nonzero>1000,"real pulse continues during state and input processing");
        }finally{runtime.close();}
        check(!runtime.isAlive()&&runtime.exitCode().orElse(-1)==0&&!runtime.wasForcedShutdown()&&runtime.nativeTemporaryFilesCleaned(),"native worker exits naturally and cleans its native files");
        check(ProcessHandle.of(pid).isEmpty(),"the exact worker process is gone");
        Files.writeString(out.resolve("result.json"),"{\"checks\":"+checks+",\"workerPid\":"+pid+",\"exitCode\":0,\"forced\":false,\"nativeTempCleaned\":true,\"nonzeroPcmSamples\":"+nonzero+"}");
        System.out.println("NesMediaProbe checks="+checks+"; nonzero PCM="+nonzero+"; worker="+pid+" exit=0 forced=false nativeTempCleaned=true");
    }
    private static boolean pixel(AvFrame a,AvFrame b,int quarter){int p=((a.height()/2)*a.width()+a.width()*quarter/4)*4;return Arrays.equals(Arrays.copyOfRange(a.rgba(),p,p+4),Arrays.copyOfRange(b.rgba(),p,p+4));}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
}
