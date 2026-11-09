package net.muxigame.console.runtime;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** MXS3 product path: real export, old actual ROM path removed, new profile and ordinary cold CPU. */
public final class Link3dsPortableSmoke {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final JsonArray checks=new JsonArray();
    private static void check(String name,boolean pass){JsonObject row=new JsonObject();row.addProperty("name",name);row.addProperty("pass",pass);checks.add(row);System.out.println((pass?"PASS ":"FAIL ")+name);System.out.flush();if(!pass)throw new AssertionError(name);}
    private static Map<String,String> tree(Path root)throws IOException {Map<String,String> result=new TreeMap<>();try(var files=Files.walk(root)){for(Path file:files.filter(Files::isRegularFile).toList())result.put(root.relativize(file).toString(),Link3dsWorker.hash(file));}return result;}
    private static Path saveFile(Path root)throws IOException {try(var files=Files.walk(root)){return files.filter(file->file.getFileName().toString().equals("muxi-console-original-save.dat")).findFirst().orElseThrow(()->new IOException("Original guest save absent"));}}
    private static int counter(Path root)throws IOException {byte[] bytes=Files.readAllBytes(saveFile(root));if(bytes.length<8)throw new IOException("Guest save incomplete");ByteBuffer data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);if(data.getInt()!=0x4d584433)throw new IOException("Guest save identity differs");return data.getInt();}
    private static void copyTree(Path source,Path target)throws IOException {try(var files=Files.walk(source)){for(Path file:files.toList()){Path destination=target.resolve(source.relativize(file));if(Files.isDirectory(file))Files.createDirectories(destination);else Files.copy(file,destination);}}}
    public static void main(String[] arguments)throws Exception {
        Path java=Path.of(arguments[0]),core=Path.of(arguments[1]),original=Path.of(arguments[3]),out=Path.of(arguments[4]);String hash=arguments[2];Files.createDirectories(out);
        JsonObject result=new JsonObject();result.addProperty("complete",false);result.add("checks",checks);result.addProperty("coreSHA256",hash);
        Link3dsRuntime runtime=null;ProcessConsoleRuntime single=null;
        try {
            Path warm=out.resolve("actual-warm-rom.3dsx");Files.copy(original,warm);List<Path> classpath=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).map(Path::of).toList();List<Link3dsRuntime.Endpoint> endpoints=new ArrayList<>();
            for(int i=0;i<2;i++){UUID machine=new UUID(i+1,0x504f5254L);Path profile=out.resolve("warm-"+i);Files.createDirectories(profile);endpoints.add(new Link3dsRuntime.Endpoint(machine,Link3dsWorker.hash(warm),new LaunchConfig(java,classpath,core,warm,out.resolve("shared-firmware"),profile.resolve("native"),Link3dsIdentity.singleOptions(machine),"n3ds")));}
            runtime=Link3dsRuntime.open(new Link3dsRuntime.Config(hash,endpoints));result.add("pairPids",JSON.toJsonTree(runtime.workerPids()));List<Path> nativePaths=runtime.workerNativeDirectories();
            for(int i=0;i<90;i++)runtime.step(PortInput.IDLE,PortInput.IDLE);for(int i=0;i<12;i++)runtime.step(new PortInput(256),PortInput.IDLE);for(int i=0;i<12;i++)runtime.step(PortInput.IDLE,PortInput.IDLE);
            Path profile=endpoints.get(0).launch().saveDirectory();check("actual original input ROM writes counter one",counter(profile)==1);
            Path state=out.resolve("portable-export.mxs3");runtime.exportEndpointTo(0,state);try(var header=new DataInputStream(Files.newInputStream(state))){check("product emits exact-DLL portable MXS3 header",header.readInt()==0x4d585333&&HexFormat.of().formatHex(header.readNBytes(32)).equals(hash));}
            runtime.close();check("both real product workers exit naturally before profile copy",runtime.naturalExit()&&!runtime.isAlive()&&nativePaths.stream().noneMatch(Files::exists));runtime=null;
            Map<String,String> before=tree(profile);Path relocated=out.resolve("different-cold-profile");copyTree(profile,relocated);
            Path retained=out.resolve("retained-original.3dsx");Files.move(warm,retained);Path cold=out.resolve("different-content/renamed.3dsx");Files.createDirectories(cold.getParent());Files.copy(retained,cold);
            check("old actual ROM path absent and new actual content bytes identical",!Files.exists(warm)&&Link3dsWorker.hash(cold).equals(Link3dsWorker.hash(original)));
            UUID machine=endpoints.get(0).machine();LaunchConfig launch=new LaunchConfig(java,classpath,core,cold,relocated.resolve("new-system"),relocated,Link3dsIdentity.singleOptions(machine),"n3ds");single=ProcessConsoleRuntime.open(launch);result.addProperty("coldPid",single.workerPid());Path nativePath=single.nativeTemporaryDirectory();
            if(arguments.length>5){Path prior=Path.of(arguments[5]);String expected=arguments[6];check("prior ABI1 source is the exact preserved real MXS3 state",Link3dsWorker.hash(prior).equals(expected));boolean refused=false;try{single.loadStateFrom(prior);}catch(IOException mismatch){refused=NativeStateBinding.mismatch(mismatch);}check("actual new worker refuses the former native-format state and preserves native game progress",refused&&counter(relocated)==1);result.addProperty("priorFormatStateSHA256",expected);result.addProperty("priorFormatRejected",true);}
            single.loadStateFrom(state);
            for(int i=0;i<12;i++)single.step(new InputFrame(List.of(new PortInput(256))));for(int i=0;i<12;i++)single.step(InputFrame.idle());
            check("ordinary MXS3 cold restore increments original CPU counter in new native profile",counter(relocated)==2);
            single.close();check("cold ordinary worker has actual guarded natural zero exit and native cleanup",!single.isAlive()&&single.exitCode().orElse(-1)==0&&!single.wasForcedShutdown()&&single.nativeTemporaryFilesCleaned()&&!Files.exists(nativePath));single=null;
            check("closed source profile remains byte-for-byte unchanged",tree(profile).equals(before));
            result.addProperty("stateSHA256",Link3dsWorker.hash(state));result.addProperty("romSHA256",Link3dsWorker.hash(cold));result.addProperty("oldActualRomAvailable",Files.exists(warm));result.add("sourceProfile",JSON.toJsonTree(before));result.add("coldProfile",JSON.toJsonTree(tree(relocated)));result.addProperty("complete",true);
        }catch(Throwable failure){result.addProperty("error",failure.toString());failure.printStackTrace(System.err);}
        finally{if(runtime!=null)try{runtime.close();}catch(Throwable failure){result.addProperty("cleanupFailure",failure.toString());}if(single!=null)try{single.close();}catch(Throwable failure){result.addProperty("cleanupFailure",failure.toString());}if(result.has("cleanupFailure"))result.addProperty("complete",false);Files.writeString(out.resolve("result.json"),JSON.toJson(result)+"\n");}
        if(!result.get("complete").getAsBoolean())System.exit(1);
    }
}
