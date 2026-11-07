package net.muxigame.console.client;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.muxigame.console.domain.*;
import net.muxigame.console.runtime.*;

/** Bounded extraction of exact packaged identities; third-party cores remain in our isolated worker. */
public final class RuntimeFiles {
    private static final Gson GSON=new Gson();private static final long MAX_EXTRACT=512L*1024*1024;
    private final Path base,runtimeRoot;private final JsonObject hashes,sizes,workerDependencies;
    private static JsonObject resource(String path)throws IOException{try(InputStream input=RuntimeFiles.class.getResourceAsStream(path)){if(input==null)throw new IOException("运行时清单缺失："+path);byte[] bytes=input.readNBytes(131073);if(bytes.length>131072)throw new IOException("运行时清单过大");return GSON.fromJson(new String(bytes,StandardCharsets.UTF_8),JsonObject.class);}catch(JsonParseException|IllegalStateException bad){throw new IOException("运行时清单无效",bad);}}
    public RuntimeFiles(Path gameDirectory)throws IOException{
        base=gameDirectory.resolve("muxi-console").toAbsolutePath().normalize();runtimeRoot=gameDirectory.resolve("natives/muxi-console").toAbsolutePath().normalize();safeDirectory(base);safeDirectory(runtimeRoot);
        hashes=resource("/META-INF/muxi/extract-hashes.json");sizes=resource("/META-INF/muxi/extract-sizes.json");workerDependencies=resource("/META-INF/muxi/worker-dependencies.json");
        if(hashes.size()>256||!hashes.keySet().equals(sizes.keySet()))throw new IOException("运行时大小与哈希清单不一致");
        for(String name:hashes.keySet())if(!name.matches("(?:worker/[A-Za-z0-9._-]+\\.jar|cores/[a-z0-9_-]+/[a-z0-9_]+\\.(?:dll|so))")||!hashes.get(name).getAsString().matches("[0-9a-f]{64}")||sizes.get(name).getAsLong()<1||sizes.get(name).getAsLong()>MAX_EXTRACT)throw new IOException("运行时清单条目无效");
    }
    private static void safeDirectory(Path directory)throws IOException{Files.createDirectories(directory);Path normal=directory.toAbsolutePath().normalize();if(Files.isSymbolicLink(normal)||!Files.isDirectory(normal,LinkOption.NOFOLLOW_LINKS)||!normal.toRealPath().equals(normal))throw new IOException("运行目录不可使用链接或重解析路径");}
    public Path root(){return base;}
    public Path extract(String name)throws IOException{
        if(!hashes.has(name))throw new IOException("当前平台没有打包此模拟器："+name);String expected=hashes.get(name).getAsString();long expectedBytes=sizes.get(name).getAsLong();Path file=runtimeRoot.resolve(name).normalize();if(!file.startsWith(runtimeRoot))throw new IOException("运行时路径无效");safeDirectory(file.getParent());
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw new IOException("运行时文件路径不安全");if(Files.size(file)==expectedBytes&&Hashing.sha256(file).equals(expected))return file;}
        try(InputStream input=RuntimeFiles.class.getResourceAsStream("/"+name)){
            if(input==null)throw new IOException("打包文件缺失："+name);Path temp=Files.createTempFile(file.getParent(),"extract-",".tmp");
            try{var digest=Hashing.sha256Digest();long count=0;try(OutputStream output=Files.newOutputStream(temp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){byte[] buffer=new byte[32768];for(int n;(n=input.read(buffer))!=-1;){count+=n;if(count>expectedBytes)throw new IOException("运行时文件超出清单大小");digest.update(buffer,0,n);output.write(buffer,0,n);}}if(count!=expectedBytes||!HexFormat.of().formatHex(digest.digest()).equals(expected))throw new IOException("运行时文件校验失败");try{Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}
        }return file;
    }
    public static String core(Platform platform){return switch(platform){case NES,FDS->"fceumm";case SNES->"bsnes2014_performance";case GB,GBC->"sameboy";case GBA->"mgba";case MD->"blastem";case SMS,GG,SG1000->"gearsystem";case PCE->"mednafen_pce_fast";case A2600->"stella2014";case A7800->"prosystem";case LYNX->"mednafen_lynx";case NGP,NGPC->"mednafen_ngp";case WS,WSC->"mednafen_wswan";case VB->"mednafen_vb";case N64->"mupen64plus_next";case ARCADE->"mame";};}
    public record Selected(LaunchConfig launch,String fingerprint){}
    public Selected select(Platform platform,Path rom,UUID instance)throws IOException{
        String os=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"windows-x86_64":"linux-x86_64";
        String suffix=os.startsWith("windows")?".dll":".so";
        Path library=extract("cores/"+os+"/"+core(platform)+suffix);
        Path system=base.resolve("firmware"),save=base.resolve("saves").resolve(instance.toString());safeDirectory(system);safeDirectory(save);String fingerprint=Hashing.sha256(library);
        if(platform==Platform.LYNX){Path firmware=system.resolve("lynxboot.img");if(!Files.isRegularFile(firmware,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(firmware)||Files.size(firmware)!=512)throw new IOException("此机型需要你自行提供已获授权的 512 字节 lynxboot.img，放入 muxi-console/firmware");fingerprint=Hashing.sha256((fingerprint+"\nlynx-firmware="+Hashing.sha256(firmware)).getBytes(StandardCharsets.UTF_8));}
        if(platform==Platform.FDS){Path firmware=system.resolve("disksys.rom");if(!Files.isRegularFile(firmware,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(firmware)||Files.size(firmware)!=8192)throw new IOException("FDS 需要你自行提供已获授权的 8192 字节 disksys.rom，放入 muxi-console/firmware");fingerprint=Hashing.sha256((fingerprint+"\nfds-firmware="+Hashing.sha256(firmware)).getBytes(StandardCharsets.UTF_8));}
        List<Path> classpath=new ArrayList<>(List.of(extract("worker/runtime.jar"),extract("worker/jna-5.14.0.jar")));Map<String,String> options=new TreeMap<>();
        if(platform==Platform.N64){if(!os.equals("windows-x86_64"))throw new IOException("当前N64硬件渲染依赖仅打包Windows x86-64");JsonArray dependencies=workerDependencies.getAsJsonArray("dependencies");if(dependencies==null||dependencies.size()!=6)throw new IOException("N64工作进程依赖清单无效");StringBuilder identity=new StringBuilder(fingerprint);Set<String> seen=new HashSet<>();for(JsonElement value:dependencies){JsonObject entry=value.getAsJsonObject();String name="worker/"+entry.get("file").getAsString();if(!seen.add(name)||!hashes.has(name)||!hashes.get(name).getAsString().equals(entry.get("sha256").getAsString())||sizes.get(name).getAsLong()!=entry.get("bytes").getAsLong())throw new IOException("N64工作进程依赖身份不一致");classpath.add(extract(name));identity.append('\n').append(name).append('=').append(hashes.get(name).getAsString());}options.put("mupen64plus-rdp-plugin","gliden64");options.put("mupen64plus-rsp-plugin","hle");options.put("mupen64plus-cpucore","pure_interpreter");identity.append('\n').append(options);fingerprint=Hashing.sha256(identity.toString().getBytes(StandardCharsets.UTF_8));}
        if(platform==Platform.ARCADE){safeDirectory(base.resolve("media"));rom=ArcadeBundle.extract(rom,base.resolve("media").resolve(Hashing.sha256(rom)));}
        Path java=Path.of(System.getProperty("java.home"),"bin",os.startsWith("windows")?"java.exe":"java");return new Selected(new LaunchConfig(java,classpath,library,rom,system,save,options),fingerprint);
    }
}
