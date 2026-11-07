package net.muxigame.console.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Every restore target is a new process with zero public/native priming steps from this test. */
public final class CoreColdLoadSmoke {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static AvFrame run(ConsoleRuntime runtime,int frames)throws IOException{AvFrame frame=null;for(int i=0;i<frames;i++)frame=runtime.step(InputFrame.idle());return frame;}
    private static void closed(ProcessConsoleRuntime runtime)throws IOException{
        runtime.close();check(!runtime.isAlive()&&runtime.exitCode().orElse(-1)==0&&!runtime.wasForcedShutdown(),"Worker exits normally");
        check(runtime.nativeTemporaryFilesCleaned()&&ProcessHandle.of(runtime.workerPid()).isEmpty(),"Worker process and owned native files released");
    }
    private static void visible(AvFrame frame,String platform){
        check(frame!=null&&frame.width()>0&&frame.height()>0&&frame.rgba().length==frame.width()*frame.height()*4,"Actual packed frame");
        check(Double.isFinite(frame.displayAspect())&&frame.displayAspect()>0,"Restored display aspect");
        byte[] rgba=frame.rgba();int first=-1;boolean distinct=false;
        for(int i=0;i<rgba.length;i+=4){int rgb=(rgba[i]&255)<<16|(rgba[i+1]&255)<<8|(rgba[i+2]&255);if(first<0)first=rgb;else if(rgb!=first){distinct=true;break;}}
        if(platform.equals("gba")){
            int i=(frame.height()/2*frame.width()+frame.width()/2)*4,r=rgba[i]&255,g=rgba[i+1]&255,b=rgba[i+2]&255;
            check(frame.width()==240&&frame.height()==160&&r<30&&((b>180&&g<30)||(g>180&&b<30)),"Original GBA mode3 executes its defined blue/green whole-screen pattern");
        }else check(distinct,"Diagnostic executes a non-uniform native picture");
    }
    private static void samePattern(AvFrame expected,AvFrame actual,String platform){
        if(platform.equals("arcade"))return; // MAME netlist pixel continuation is explicitly not guaranteed.
        boolean same=expected.width()==actual.width()&&expected.height()==actual.height();
        if(same)for(int quarter:new int[]{1,3})for(int channel=0;channel<3;channel++){
            int offset=((actual.height()*quarter/4)*actual.width()+actual.width()/2)*4+channel;
            same&=Math.abs((expected.rgba()[offset]&255)-(actual.rgba()[offset]&255))<8;
        }
        check(same,"Fresh restore retains the source diagnostic's upper/lower color pattern");
    }
    private static byte[] wrongSram(byte[] state)throws IOException{
        var input=new DataInputStream(new ByteArrayInputStream(state));check(input.readInt()==0x4d585331,"MXS1 format preserved");
        byte[][] parts=new byte[3][];for(int i=0;i<3;i++){int length=input.readInt();if(length<0||length>input.available())throw new IOException("Invalid source state");parts[i]=input.readNBytes(length);}
        check(input.available()==0,"MXS1 still has only three components");parts[1]=Arrays.copyOf(parts[1],parts[1].length+1);
        var bytes=new ByteArrayOutputStream();try(var output=new DataOutputStream(bytes)){output.writeInt(0x4d585331);for(byte[] part:parts){output.writeInt(part.length);output.write(part);}}return bytes.toByteArray();
    }
    public static void main(String[] args)throws Exception{
        String platform=args[5];Path output=Path.of(args[4]);List<Path> cp=Arrays.stream(args[1].split(File.pathSeparator)).map(Path::of).toList();
        Map<String,String> options=platform.equals("n64")?Map.of("mupen64plus-rdp-plugin","gliden64","mupen64plus-rsp-plugin","hle","mupen64plus-cpucore","pure_interpreter"):Map.of();
        LaunchConfig config=new LaunchConfig(Path.of(args[0]),cp,Path.of(args[2]),Path.of(args[3]),Path.of(args[6]),output.resolve("save"),options);
        byte[] checkpoint,sram;AvFrame sourceImage;ProcessConsoleRuntime source=ProcessConsoleRuntime.open(config);long sourcePid=source.workerPid();
        try{
            AvFrame image=run(source,Integer.parseInt(args[7]));
            if(platform.equals("gba")){for(int i=0;i<18;i++)image=source.step(new InputFrame(List.of(new PortInput(1<<3))));image=run(source,3);}
            visible(image,platform);sourceImage=image;checkpoint=source.saveState();sram=source.readSram();
            check(checkpoint.length>16,"Normal source produced a real core checkpoint");
            Files.write(output.resolve("normal.mxs1"),checkpoint);System.out.println("SOURCE "+platform+" "+source.info().name()+" state="+checkpoint.length+" pid="+sourcePid);
        }finally{closed(source);}
        ProcessConsoleRuntime target=ProcessConsoleRuntime.open(config);long targetPid=target.workerPid();
        try{
            check(targetPid!=sourcePid,"Restore target is a different process");
            // The first state API on this new process is the actual restore.
            // Do not put runtime.step(), a warm-up loop or saveState() here.
            target.loadState(checkpoint);
            check(Arrays.equals(sram,target.readSram()),"Cold restore immediately recovers complete SRAM");
            AvFrame first=target.step(InputFrame.idle());check(first.number()==0,"Bootstrap does not advance the public frame counter");
            AvFrame after=run(target,20);visible(after,platform);samePattern(sourceImage,after,platform);
            byte[] beforeBad=target.readSram();
            try{target.loadState(wrongSram(checkpoint));throw new AssertionError("Wrong SRAM accepted");}
            catch(IOException expected){check(expected.getMessage().contains("previous running state restored"),"Bad state restores the previous native state");}
            check(target.isAlive()&&Arrays.equals(beforeBad,target.readSram()),"Bad state preserves the active SRAM");visible(run(target,5),platform);
            System.out.println("COLD_RESTORE "+platform+" targetPublicStepsBeforeLoad=0 raster="+after.width()+"x"+after.height()+" aspect="+after.displayAspect()+" pid="+targetPid);
        }finally{closed(target);}
        ProcessConsoleRuntime freshSave=ProcessConsoleRuntime.open(config);
        try{
            byte[] original=freshSave.readSram();byte[] state=freshSave.saveState();check(state.length>16,"Save before any public step succeeds");
            if(platform.equals("nes")||platform.equals("gba"))check(Arrays.equals(original,freshSave.readSram()),"Already-ready software core retains its untouched initial SRAM");
            if(platform.equals("gba"))check(original.length==131072&&freshSave.readSram().length==131072,"Cold mGBA save retains the original undetected 128 KiB region");
            check(freshSave.step(InputFrame.idle()).number()==0,"Cold save preserves first public frame number");
            System.out.println("COLD_SAVE "+platform+" initialSram="+original.length+" state="+state.length);
        }finally{closed(freshSave);}
        System.out.println("COLD_LOAD_CHECKS "+checks);
    }
}
