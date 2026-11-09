import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.muxigame.console.client.RuntimeFiles;
import net.muxigame.console.client.transfer.*;
import net.muxigame.console.domain.*;
import net.muxigame.console.runtime.*;

/** Real worker regression for a preserved Wii state; never prepares or alters an MC world. */
public final class WiiSdCheckpointProbe {
    private static final UUID MACHINE=UUID.fromString("8dc7b3e0-800e-4f49-a895-93caf951d10f");
    private static final String SD="User/Load/WiiSD.raw",GAME="User/Wii/shared2/muxi-console-original-save.dat";
    private static int checks;
    private static void check(boolean condition,String description)throws IOException{
        if(!condition)throw new IOException("Checkpoint regression: "+description);
        checks++;System.out.println("PASS "+checks+" "+description);System.out.flush();
    }
    private static long savedGameFrame(Path profile)throws IOException{
        byte[] data=Files.readAllBytes(profile.resolve(GAME));
        ByteBuffer words=ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        if(data.length!=64||words.getInt()!=0x4d585749||words.getInt()!=2)throw new IOException("Original diagnostic native-save record absent");
        return Integer.toUnsignedLong(words.getInt());
    }
    private static void idle(ProcessConsoleRuntime runtime,int frames)throws IOException{for(int i=0;i<frames;i++)runtime.step(InputFrame.idle());}
    private static void press(ProcessConsoleRuntime runtime)throws IOException{
        for(int i=0;i<16;i++)runtime.step(new InputFrame(List.of(new PortInput(1<<8))));
        idle(runtime,16);
    }
    private static Map<String,Object> close(ProcessConsoleRuntime runtime)throws IOException{
        runtime.close();
        Map<String,Object> result=new LinkedHashMap<>();result.put("pid",runtime.workerPid());result.put("exit",runtime.exitCode().orElse(-1));result.put("forced",runtime.wasForcedShutdown());result.put("alive",runtime.isAlive());result.put("nativeClean",runtime.nativeTemporaryFilesCleaned());
        check(!runtime.isAlive()&&runtime.exitCode().orElse(-1)==0&&!runtime.wasForcedShutdown()&&runtime.nativeTemporaryFilesCleaned(),"actual Windows process signaled naturally and native files cleaned");return result;
    }
    private static void verifySd(Path profile,NativeProfileArchive.Snapshot archive)throws IOException{
        Path original=profile.resolve(SD);long size=Files.size(original);
        check(size==128L*1024*1024,"actual core-created SD image is the complete 128 MiB file");
        try(var zip=new ZipFile(archive.path().toFile())){
            ZipEntry sd=zip.getEntry(SD);check(sd!=null&&sd.getSize()==size,"machine profile includes the complete SD image");
            var digest=Hashing.sha256Digest();long count=0;
            try(var input=zip.getInputStream(sd)){byte[] block=new byte[65536];for(int n;(n=input.read(block))!=-1;){digest.update(block,0,n);count+=n;}}
            check(count==size&&HexFormat.of().formatHex(digest.digest()).equals(Hashing.sha256(original)),"every archived SD byte matches the real native image while worker owns its writer");
        }
    }
    public static void main(String[] arguments)throws Exception{
        Path game=Path.of(arguments[0]),rom=Path.of(arguments[1]),oldState=Path.of(arguments[2]),oldProfile=Path.of(arguments[3]),newCore=Path.of(arguments[4]),out=Path.of(arguments[5]);
        Files.createDirectories(out);Map<String,Object> report=new LinkedHashMap<>();List<Map<String,Object>> workers=new ArrayList<>();report.put("workers",workers);report.put("complete",false);
        String stateSha=Hashing.sha256(oldState),profileSha=Hashing.sha256(oldProfile);report.put("sourceStateSHA256",stateSha);report.put("sourceProfileSHA256",profileSha);report.put("newCoreSHA256",Hashing.sha256(newCore));
        RuntimeFiles files=new RuntimeFiles(game);RuntimeFiles.Selected selected=files.select(Platform.WII,rom,new MachineSaveKey(MACHINE,Hashing.sha256(rom)));LaunchConfig old=selected.launch();
        LaunchConfig launch=new LaunchConfig(old.javaExecutable(),old.classpath(),newCore.toAbsolutePath(),rom.toAbsolutePath(),old.systemDirectory(),old.saveDirectory(),old.options(),"wii");
        report.put("options",launch.options());report.put("machine",MACHINE.toString());report.put("romSHA256",Hashing.sha256(rom));report.put("compiledRuntime",old.classpath().stream().filter(p->p.getFileName().toString().equals("runtime.jar")).findFirst().map(p->{try{return Hashing.sha256(p);}catch(IOException e){throw new UncheckedIOException(e);}}).orElse(""));
        ProcessConsoleRuntime runtime=null;String operation="restore_preserved_profile";
        try{
            NativeProfileArchive.restore(launch.saveDirectory(),"wii",oldProfile,()->true);
            operation="expand_preserved_state";Path expanded=StateSpool.expand(oldState,out.resolve("preserved-expanded.mxs1"),512L*1024*1024,()->true,n->{});
            operation="open_new_core";runtime=ProcessConsoleRuntime.open(launch);report.put("coreInfo",runtime.info());
            operation="load_old_core_state";runtime.loadStateFrom(expanded);check(true,"new fixed core accepts the preserved original old-core MXS1 state");
            operation="run_old_cold";idle(runtime,30);long initial=savedGameFrame(launch.saveDirectory());check(initial>0,"preserved game's real big-endian native save has a completed application frame");
            operation="actual_A_input";press(runtime);long first=savedGameFrame(launch.saveDirectory());check(first>initial,"cold CPU resumes and an actual A edge writes a later native game frame");
            operation="save_first_core";Path saved=out.resolve("new-checkpoint.mxs1");runtime.saveStateTo(saved);check(Files.size(saved)>100L*1024*1024,"full actual large core state serialized");
            try(var header=new DataInputStream(Files.newInputStream(saved))){check(header.readInt()==0x4d585331,"Wii preserves the original MXS1 envelope");}
            operation="pack_first_profile";NativeProfileArchive.Snapshot packed=NativeProfileArchive.pack(launch.saveDirectory(),"wii",out.resolve("new-profile.zip"),()->true,n->{});verifySd(launch.saveDirectory(),packed);report.put("firstProfile",packed);report.put("newStateSHA256",Hashing.sha256(saved));
            operation="compress_first_state";Path compressed=StateSpool.compress(saved,out.resolve("new-state.mxz1"),512L*1024*1024,()->true,n->{});report.put("transportStateBytes",Files.size(compressed));
            operation="close_first_worker";workers.add(close(runtime));runtime=null;
            operation="restore_closed_profile";NativeProfileArchive.restore(launch.saveDirectory(),"wii",packed.path(),()->true);check(savedGameFrame(launch.saveDirectory())==first,"complete profile restores the actual updated game save");
            operation="open_second_core";runtime=ProcessConsoleRuntime.open(launch);
            operation="restore_new_state";runtime.loadStateFrom(saved);idle(runtime,30);check(savedGameFrame(launch.saveDirectory())==first,"new checkpoint cold-restores matching native game progress");
            operation="second_actual_A_input";press(runtime);check(savedGameFrame(launch.saveDirectory())>first,"second cold worker resumes actual game inputs");
            operation="save_final_core";Path finalState=out.resolve("final-checkpoint.mxs1");runtime.saveStateTo(finalState);
            operation="pack_final_profile";NativeProfileArchive.Snapshot finalProfile=NativeProfileArchive.pack(launch.saveDirectory(),"wii",out.resolve("final-profile.zip"),()->true,n->{});verifySd(launch.saveDirectory(),finalProfile);report.put("finalProfile",finalProfile);report.put("finalStateSHA256",Hashing.sha256(finalState));
            operation="close_final_worker";workers.add(close(runtime));runtime=null;
            check(Hashing.sha256(oldState).equals(stateSha)&&Hashing.sha256(oldProfile).equals(profileSha),"original failed-run state and profile bytes remain unchanged");
            report.put("complete",true);
        }catch(Throwable failure){report.put("failedOperation",operation);report.put("failure",TransferFailure.diagnostic(failure));report.put("filesystemReason",TransferFailure.filesystemReason(failure));throw failure;}
        finally{
            if(runtime!=null)try{workers.add(close(runtime));}catch(Exception cleanup){report.put("cleanupFailure",TransferFailure.diagnostic(cleanup));}
            report.put("checks",checks);report.put("sourceFilesUnchanged",Hashing.sha256(oldState).equals(stateSha)&&Hashing.sha256(oldProfile).equals(profileSha));
            // Snapshot contains a Path; publish a plain path spelling rather than reflective NIO internals.
            for(String key:List.of("firstProfile","finalProfile"))if(report.get(key) instanceof NativeProfileArchive.Snapshot p)report.put(key,Map.of("path",p.path().toString(),"bytes",p.size(),"sha256",p.hash(),"files",p.files(),"expandedBytes",p.expandedBytes()));
            Files.writeString(out.resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        }
    }
}
