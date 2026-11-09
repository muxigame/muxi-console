package net.muxigame.console.mc;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.muxigame.console.domain.Hashing;
import net.muxigame.console.runtime.LinkDsIdentity;
import net.muxigame.console.runtime.Link3dsIdentity;

/** Actual bridge identity and separately proven single-state compatibility identity. */
public record LinkCoreIdentity(String bridge,String single) {
    public static LinkCoreIdentity bundled()throws IOException {
        return bundled("gb");
    }
    private static String backend(String platform)throws IOException{return switch(platform){case "gb","gbc"->"sameboy";case "gba"->"mgba";case "nds"->"melondsds";case "n3ds"->"azahar";default->throw new IOException("此机型尚无连线核心");};}
    public static String library(String platform)throws IOException{return "cores/windows-x86_64/"+backend(platform)+"_link.dll";}
    public static String stateFingerprint(String platform,UUID machine)throws IOException{
        String base=bundled(platform).single();if(!platform.equals("nds")&&!platform.equals("n3ds"))return base;
        try(var input=LinkCoreIdentity.class.getResourceAsStream("/META-INF/muxi/worker-dependencies.json")){
            if(input==null)throw new IOException("工作进程依赖身份缺失");JsonArray entries=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("dependencies");List<String> dependencies=new ArrayList<>();for(JsonElement entry:entries){var value=entry.getAsJsonObject();dependencies.add("worker/"+value.get("file").getAsString()+"="+value.get("sha256").getAsString());}return platform.equals("n3ds")?Link3dsIdentity.singleFingerprint(base,dependencies,machine):LinkDsIdentity.singleFingerprint(base,dependencies,machine);
        }catch(RuntimeException invalid){throw new IOException("单机工作进程身份无效",invalid);}
    }
    public static LinkCoreIdentity bundled(String platform)throws IOException {
        try(var input=LinkCoreIdentity.class.getResourceAsStream("/META-INF/muxi/"+backend(platform)+"-link.json")){
            if(input==null)throw new IOException("此安装包尚未包含连线核心");
            JsonObject pin=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            if(platform.equals("n3ds")&&(pin.getAsJsonObject("nativeApi").get("profileStateAbi").getAsInt()<2||!pin.getAsJsonObject("nativeDiagnostics").has("activeUdsCheckpointRestore")||!pin.getAsJsonObject("nativeDiagnostics").get("activeUdsCheckpointRestore").getAsBoolean()))throw new IOException("此 3DS 核心尚未通过无线进度恢复验证");
            return new LinkCoreIdentity(Hashing.normalizeSha256(pin.getAsJsonObject("platforms").getAsJsonObject("windows-x86_64").get("librarySha256").getAsString()),Hashing.normalizeSha256(pin.getAsJsonObject("baseSingleCoreCompatibility").get("librarySha256").getAsString()));
        }catch(RuntimeException invalid){throw new IOException("连线核心清单无效");}
    }
}
