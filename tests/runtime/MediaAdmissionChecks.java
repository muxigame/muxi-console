package net.muxigame.console.runtime;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipOutputStream;
import net.muxigame.console.domain.*;

/** Actual final-artifact admission of the 21 original diagnostics; no source-class or bare-core shortcut. */
public final class MediaAdmissionChecks {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void rejected(Path file,Platform platform)throws IOException{try{new RomInspector().inspect(file,platform);throw new AssertionError("Invalid media was admitted");}catch(DomainException|IOException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args[0]).toAbsolutePath().normalize(),artifact=Path.of(args[2]).toRealPath();JsonArray items=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();
        check(Path.of(RomInspector.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(artifact),"RomInspector is loaded from the exact pinned product jar");check(Platform.values().length==21&&items.size()==21,"all 21 diagnostic platforms represented");Set<Platform> seen=EnumSet.noneOf(Platform.class);JsonArray results=new JsonArray();
        for(JsonElement value:items){JsonObject item=value.getAsJsonObject();Platform platform=Platform.fromId(item.get("platform").getAsString());check(seen.add(platform),"unique platform");Path source=Path.of(item.get("source").getAsString());String sourceHash=item.get("sourceSha256").getAsString();check(Hashing.sha256(source).equals(sourceHash),"original diagnostic still matches matrix pin");Path admittedFile=source;
            if(platform==Platform.ARCADE){Path pong=output.resolve("pong.zip");try(ZipOutputStream ignored=new ZipOutputStream(Files.newOutputStream(pong,StandardOpenOption.CREATE_NEW))){}admittedFile=output.resolve("safe.mxarc");ArcadeBundle.pack(pong,List.of(),admittedFile);rejected(pong,Platform.ARCADE);}
            RomDescriptor selected=new RomInspector().inspect(admittedFile,platform),automatic=new RomInspector().inspect(admittedFile,null);check(selected.platform()==platform&&automatic.platform()==platform,"selected and automatic admission agree");check(selected.sha256().equals(Hashing.sha256(admittedFile))&&selected.size()==Files.size(admittedFile),"admitted descriptor matches actual bytes");
            JsonObject row=new JsonObject();row.addProperty("platform",platform.id());row.addProperty("matrixSource",source.toString());row.addProperty("matrixSourceSha256",sourceHash);row.addProperty("admittedFile",admittedFile.toString());row.addProperty("sha256",selected.sha256());row.addProperty("bytes",selected.size());row.addProperty("detection",selected.detection());row.addProperty("mameSafeEnvelope",platform==Platform.ARCADE);row.addProperty("admitted",true);results.add(row);System.out.println(platform.id()+" actual admission: "+selected.detection());
        }
        check(seen.equals(EnumSet.allOf(Platform.class)),"matrix has no missing product platform");byte[] bad=new byte[16];bad[0]=0x7f;bad[1]='E';bad[2]='L';bad[3]='F';bad[4]=0;bad[5]=16;bad[6]='B';bad[7]='S';bad[8]='9';bad[9]='3';Path hostObject=output.resolve("host-object.o");Files.write(hostObject,bad);rejected(hostObject,Platform.LYNX);
        JsonObject report=new JsonObject();report.addProperty("productJar",artifact.toString());report.addProperty("productSha256",Hashing.sha256(artifact));report.addProperty("platformsAdmitted",seen.size());report.addProperty("checks",checks);report.addProperty("bareCoreBypass",false);report.add("platforms",results);Files.writeString(output.resolve("admission.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report),StandardCharsets.UTF_8);System.out.println("MediaAdmissionChecks: "+checks+" passed; 21 actual product admission decisions");
    }
}
