package net.muxigame.console.client;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.muxigame.console.domain.*;
import net.muxigame.console.runtime.*;

/** Extracts our worker and independently obtained libraries only after checking embedded content identities. */
public final class RuntimeFiles {
    private static final Gson GSON=new Gson();
    private final Path base,runtimeRoot;private final JsonObject hashes;
    public RuntimeFiles(Path gameDirectory)throws IOException{
        base=gameDirectory.resolve("muxi-console");runtimeRoot=gameDirectory.resolve("natives/muxi-console").toAbsolutePath().normalize();Files.createDirectories(base);
        try(var input=RuntimeFiles.class.getResourceAsStream("/META-INF/muxi/extract-hashes.json")){if(input==null)throw new IOException("运行时清单缺失");hashes=GSON.fromJson(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8),JsonObject.class);}
    }
    public Path root(){return base;}
    public Path extract(String name)throws IOException{
        if(!hashes.has(name))throw new IOException("当前平台没有打包此模拟器："+name);String expected=hashes.get(name).getAsString();Path file=runtimeRoot.resolve(name).normalize();
        if(!file.startsWith(runtimeRoot))throw new IOException("运行时路径无效");
        if(Files.isRegularFile(file)&&Hashing.sha256(file).equals(expected))return file;
        try(var input=RuntimeFiles.class.getResourceAsStream("/"+name)){
            if(input==null)throw new IOException("打包文件缺失："+name);Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"extract-",".tmp");
            try{Files.copy(input,temp,StandardCopyOption.REPLACE_EXISTING);if(!Hashing.sha256(temp).equals(expected))throw new IOException("运行时文件校验失败");Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}
        }return file;
    }
    public static String core(Platform platform){return switch(platform){case NES->"fceumm";case SNES->"bsnes2014_performance";case GB,GBC->"sameboy";case GBA->"mgba";case MD->"blastem";case SMS,GG,SG1000->"gearsystem";case PCE->"mednafen_pce_fast";case A2600->"stella2014";case A7800->"prosystem";case LYNX->"mednafen_lynx";case NGP,NGPC->"mednafen_ngp";case WS,WSC->"mednafen_wswan";case VB->"mednafen_vb";case N64->throw new IllegalArgumentException("N64 硬件渲染后端尚未实现");};}
    public record Selected(LaunchConfig launch,String fingerprint){}
    public Selected select(Platform platform,Path rom,UUID instance)throws IOException{
        String os=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"windows-x86_64":"linux-x86_64";
        String suffix=os.startsWith("windows")?".dll":".so";
        Path library=extract("cores/"+os+"/"+core(platform)+suffix);
        Path system=base.resolve("firmware"),save=base.resolve("saves").resolve(instance.toString());Files.createDirectories(system);Files.createDirectories(save);
        if(platform==Platform.LYNX&&!Files.isRegularFile(system.resolve("lynxboot.img")))throw new IOException("此机型需要你自行提供已获授权的 lynxboot.img，放入 muxi-console/firmware");
        Path java=Path.of(System.getProperty("java.home"),"bin",os.startsWith("windows")?"java.exe":"java");
        return new Selected(new LaunchConfig(java,List.of(extract("worker/runtime.jar"),extract("worker/jna-5.14.0.jar")),library,rom,system,save,Map.of()),Hashing.sha256(library));
    }
}
