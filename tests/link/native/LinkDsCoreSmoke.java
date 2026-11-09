package net.muxigame.console.tests.link;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32;
import net.muxigame.console.runtime.*;

/** Actual two-process DS core smoke; radio exchange is reported independently from AV/input/state capabilities. */
public final class LinkDsCoreSmoke {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static String sha(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static long crc(AvFrame frame){var crc=new CRC32();crc.update(frame.rgba());return crc.getValue();}
    private static int color(AvFrame frame){return color(frame,128,80);}
    private static int color(AvFrame frame,int x,int y){byte[] rgba=frame.rgba();int at=(y*256+x)*4;return (rgba[at]&255)<<16|(rgba[at+1]&255)<<8|rgba[at+2]&255;}
    private static boolean red(AvFrame frame){int color=color(frame);return (color>>>16)>200&&(color>>>8&255)<32&&(color&255)<32;}
    private static boolean green(AvFrame frame){int color=color(frame);return (color>>>16)<32&&(color>>>8&255)>200&&(color&255)<32;}
    public static void main(String[] args)throws Exception{
        if(args.length!=7)throw new IllegalArgumentException("java core coreHash originalLeft originalRight output wireless");Path java=Path.of(args[0]),core=Path.of(args[1]),rom=Path.of(args[3]),rightRom=Path.of(args[4]),output=Path.of(args[5]);boolean wireless=Boolean.parseBoolean(args[6]);Files.createDirectories(output);
        List<Path> cp=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).map(Path::of).toList();List<LinkDsRuntime.Endpoint> endpoints=new ArrayList<>();
        for(int i=0;i<2;i++){Path content=i==0?rom:rightRom,save=Files.createDirectories(output.resolve("machine-"+i)),system=Files.createDirectories(save.resolve("system"));endpoints.add(new LinkDsRuntime.Endpoint(new UUID(0,i+1),sha(content),new LaunchConfig(java,cp,core,content,system,save,Map.of("melonds_boot_mode","direct","melonds_touch_mode","touch"),"nds")));}
        check(!LinkDsIdentity.machineMac(endpoints.get(0).machine()).equals(LinkDsIdentity.machineMac(endpoints.get(1).machine())),"machines have stable distinct local/unicast MACs");
        LinkDsRuntime runtime=LinkDsRuntime.open(new LinkDsRuntime.Config(args[2],endpoints));LinkDsRuntime.NetworkStats[] stats=new LinkDsRuntime.NetworkStats[2];List<Long> pids=runtime.workerPids();
        try{
            check(pids.size()==2&&!pids.get(0).equals(pids.get(1)),"two independent DS processes, never two instances in one native singleton");
            LinkRuntime.Frame before=null;for(int i=0;i<180;i++){before=runtime.step(PortInput.IDLE,PortInput.IDLE);if(red(before.left())&&red(before.right()))break;}
            check(before.left().width()==256&&before.left().height()==384&&before.right().surfaces().size()==2,"both actual native dual-screen surfaces cross the wire");
            System.out.println("DS ready colors="+Integer.toHexString(color(before.left()))+","+Integer.toHexString(color(before.right()))+" frame="+before.number());
            check(red(before.left())&&red(before.right()),"both original diagnostics actually complete boot and paint their released-key color");
            LinkRuntime.Frame after=null;boolean timely=false;for(int i=0;i<12;i++){after=runtime.step(new PortInput(256),PortInput.IDLE);System.out.println("DS A frame "+(i+1)+" colors="+Integer.toHexString(color(after.left()))+","+Integer.toHexString(color(after.right())));if(i<3&&green(after.left())&&red(after.right()))timely=true;}
            System.out.println("DS input colors="+Integer.toHexString(color(after.left()))+","+Integer.toHexString(color(after.right())));
            check(timely,"native A changes only the first machine's diagnostic color within three frames");
            Path state=output.resolve("single-endpoint.mxs1");runtime.exportEndpointTo(0,state);check(Files.size(state)>8*1024*1024,"actual DS state uses bounded disk spooling instead of GB IPC byte limits");
            boolean rejected=false;try{runtime.restoreEndpointFrom(0,state);}catch(IOException expected){rejected=true;}check(rejected,"running wireless queues cannot be falsely restored from an individual MXS1");
            for(int i=0;i<2;i++)stats[i]=runtime.networkStats(i);
            if(wireless){
                for(int i=0;i<120;i++){after=runtime.step(PortInput.IDLE,PortInput.IDLE);if((color(after.left(),16,16)>>>8&255)>200&&(color(after.right(),16,16)>>>8&255)>200)break;}
                for(int i=0;i<2;i++)stats[i]=runtime.networkStats(i);System.out.println("DS radio stats="+Arrays.toString(stats)+" CPU witness colors="+Integer.toHexString(color(after.left(),16,16))+","+Integer.toHexString(color(after.right(),16,16)));
                runtime.exportEndpointTo(1,output.resolve("second-endpoint.mxs1"));
                check(stats[0].sentPackets()>0&&stats[1].receivedPackets()>0&&stats[1].sentPackets()>0&&stats[0].receivedPackets()>0,"both ARM7 CPUs transmit actual DS MAC packets through the native NETPACKET callbacks");
                check((color(after.left(),16,16)>>>8&255)>200&&(color(after.right(),16,16)>>>8&255)>200,"both ARM7 CPUs actually receive and recognize their distinct peer payload");
            }
        }finally{runtime.close();}
        check(runtime.naturalExit(),"both DS workers exit zero and remove only their native directories");
        String report="{\"checks\":"+checks+",\"coreSHA256\":\""+args[2]+"\",\"originalROM_SHA256\":\""+sha(rom)+"\",\"workers\":"+pids+",\"naturalExit\":true,\"wirelessHardwareExchangeProven\":"+(stats[0].sentPackets()>0&&stats[1].receivedPackets()>0&&stats[1].sentPackets()>0&&stats[0].receivedPackets()>0)+",\"packets\":[["+stats[0].sentPackets()+","+stats[0].receivedPackets()+"],["+stats[1].sentPackets()+","+stats[1].receivedPackets()+"]]}";
        Files.writeString(output.resolve("result.json"),report);System.out.println(report);
    }
}
