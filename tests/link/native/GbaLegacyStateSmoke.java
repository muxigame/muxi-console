package net.muxigame.console.tests.link;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import net.muxigame.console.runtime.*;

/** Imports an actual old-runtime ordinary save whose raw CPU/SRAM is newer than its public SRAM field. */
public final class GbaLegacyStateSmoke {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static String sha(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static byte[][] parts(byte[] bytes)throws Exception{var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=0x4d585331)throw new IOException("Not MXS1");byte[][] fields=new byte[3][];for(int i=0;i<3;i++){int size=in.readInt();if(size<0||size>in.available())throw new IOException();fields[i]=in.readNBytes(size);}if(in.available()!=0)throw new IOException();return fields;}
    private static int count(LinkRuntime runtime,int port)throws Exception{return parts(runtime.exportEndpoint(port))[1][1]&255;}
    private static void press(LinkRuntime runtime)throws Exception{for(int i=0;i<3;i++)runtime.step(new PortInput(256),PortInput.IDLE);for(int i=0;i<3;i++)runtime.step(PortInput.IDLE,PortInput.IDLE);}
    private static void closed(LinkRuntime runtime){check(runtime.exitCode().orElse(-1)==0&&!runtime.wasForcedShutdown()&&runtime.nativeTempCleaned(),"actual GBA worker natural exit and native cleanup");}
    public static void main(String[] args)throws Exception{
        if(args.length!=6)throw new IllegalArgumentException("java bridge leftROM rightROM oldMixedMXS1 output");Path java=Path.of(args[0]),bridge=Path.of(args[1]),left=Path.of(args[2]),right=Path.of(args[3]),legacy=Path.of(args[4]),output=Path.of(args[5]);Files.createDirectories(output);byte[] old=Files.readAllBytes(legacy);byte[][] fields=parts(old);
        check((fields[1][1]&255)==1,"original old save retains the lagging public field");
        check((fields[0][0x21001]&255)==2,"actual serialized CPU EWRAM has the newer count before import");
        List<Path> cp=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).map(Path::of).toList();var config=new LinkRuntime.Config(java,cp,bridge,sha(bridge),output.resolve("system"),List.of(new LinkRuntime.Endpoint(new UUID(0,1),"gba",sha(left),left),new LinkRuntime.Endpoint(new UUID(0,2),"gba",sha(right),right)));
        LinkRuntime runtime=LinkRuntime.open(config);byte[] combined;
        try{for(int i=0;i<20;i++)runtime.step(PortInput.IDLE,PortInput.IDLE);runtime.restoreEndpoint(0,old);check(count(runtime,0)==2,"legacy import keeps the actual newer SRAM instead of rolling back to outer one");check(count(runtime,1)==0,"legacy normalization never rewrites the other physical machine");press(runtime);check(count(runtime,0)==3,"restored actual CPU and authoritative SRAM continue together within three frames");combined=runtime.saveLinkState();Files.write(output.resolve("normalized.mxs1"),runtime.exportEndpoint(0));}
        finally{runtime.close();closed(runtime);}
        LinkRuntime cold=LinkRuntime.open(config);try{cold.loadLinkState(combined);check(count(cold,0)==3&&count(cold,1)==0,"cold coordinator checkpoint preserves both independent endpoints");press(cold);check(count(cold,0)==4&&count(cold,1)==0,"cold restored CPU continues without replaying legacy stale SRAM");}finally{cold.close();closed(cold);}
        Files.writeString(output.resolve("result.json"),"{\"checks\":"+checks+",\"legacySHA256\":\""+sha(legacy)+"\",\"bridgeSHA256\":\""+sha(bridge)+"\",\"oldOuterCounter\":1,\"actualRawCounter\":2,\"importCounter\":2,\"nextCounter\":3,\"coldNextCounter\":4,\"naturalWorkers\":2}");System.out.println("GbaLegacyStateSmoke "+checks+" real checks passed");
    }
}
