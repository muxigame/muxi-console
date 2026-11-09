import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import net.muxigame.console.client.RuntimeFiles;
import net.muxigame.console.client.transfer.*;
import net.muxigame.console.domain.*;
import net.muxigame.console.runtime.*;

/** Two actual Wii processes prove what moving a closed native profile really does. */
public final class WiiNativePathProbe {
    private static final String SAVE="User/Wii/shared2/muxi-console-original-save.dat";
    private static int checks;
    private static void check(boolean ok,String label)throws IOException{if(!ok)throw new IOException(label);checks++;System.out.println("PASS "+checks+" "+label);System.out.flush();}
    private static long gameFrame(Path profile)throws IOException{
        byte[] data=Files.readAllBytes(profile.resolve(SAVE));ByteBuffer words=ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        if(data.length!=64||words.getInt()!=0x4d585749||words.getInt()!=2)throw new IOException("Actual original Wii save record is absent");return Integer.toUnsignedLong(words.getInt());
    }
    private static void idle(ProcessConsoleRuntime runtime,int count)throws IOException{for(int i=0;i<count;i++)runtime.step(InputFrame.idle());}
    private static void press(ProcessConsoleRuntime runtime)throws IOException{for(int i=0;i<24;i++)runtime.step(new InputFrame(List.of(new PortInput(1<<8))));idle(runtime,24);}
    private static Map<String,String> tree(Path profile)throws IOException{
        Map<String,String> result=new TreeMap<>();try(var walk=Files.walk(profile)){for(Path path:walk.filter(Files::isRegularFile).sorted().toList())result.put(profile.relativize(path).toString().replace('\\','/'),Hashing.sha256(path));}return result;
    }
    private static Map<String,Object> closure(ProcessConsoleRuntime runtime)throws IOException{
        runtime.close();Map<String,Object> record=Map.of("pid",runtime.workerPid(),"exit",runtime.exitCode().orElse(-1),"forced",runtime.wasForcedShutdown(),"alive",runtime.isAlive(),"nativeClean",runtime.nativeTemporaryFilesCleaned());
        check(!runtime.isAlive()&&runtime.exitCode().orElse(-1)==0&&!runtime.wasForcedShutdown()&&runtime.nativeTemporaryFilesCleaned(),"actual native writer signaled zero and closed before touching its profile");return record;
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),originalRom=Path.of(args[1]);Files.createDirectories(root);Path oldRom=root.resolve("old-rom/actually-loaded.dol"),newRom=root.resolve("new-rom/renamed-content.dol");Files.createDirectories(oldRom.getParent());Files.createDirectories(newRom.getParent());Files.copy(originalRom,oldRom);Files.copy(originalRom,newRom);
        UUID machine=UUID.fromString("45838b5e-a05f-4fe2-9b31-3d386c92b537");String hash=Hashing.sha256(originalRom);RuntimeFiles warmFiles=new RuntimeFiles(root.resolve("warm")),coldFiles=new RuntimeFiles(root.resolve("cold"));
        LaunchConfig warm=warmFiles.select(Platform.WII,oldRom,new MachineSaveKey(machine,hash)).launch();Path profile=warm.saveDirectory(),coldProfile=coldFiles.machineProfile(machine,Platform.WII);
        Map<String,Object> report=new LinkedHashMap<>();List<Map<String,Object>> workers=new ArrayList<>();report.put("workers",workers);report.put("complete",false);report.put("romSHA256",hash);report.put("machine",machine.toString());report.put("actualCoreSHA256",Hashing.sha256(warm.core()));report.put("oldActualRom",oldRom.toString());report.put("newActualRom",newRom.toString());
        ProcessConsoleRuntime runtime=null;String operation="warm_boot";Map<String,String> before=null;
        try{
            runtime=ProcessConsoleRuntime.open(warm);idle(runtime,480);press(runtime);long first=gameFrame(profile);check(first>0,"actual warm CPU and A input write native game progress");
            operation="warm_save";Path initial=root.resolve("warm-initial.mxs1");runtime.saveStateTo(initial);runtime.loadStateFrom(initial);idle(runtime,4);Path state=root.resolve("warm-final.mxs1");runtime.saveStateTo(state);report.put("stateSHA256",Hashing.sha256(state));
            operation="warm_close";workers.add(closure(runtime));runtime=null;before=tree(profile);report.put("oldTreeBefore",before);check(before.containsKey("User/Load/WiiSD.raw"),"closed actual warm profile includes its complete native SD image");
            operation="archive_closed_profile";Path zipped=root.resolve("closed-profile.zip");NativeProfileArchive.Snapshot archive=NativeProfileArchive.pack(profile,"wii",zipped,()->true,n->{});report.put("closedProfileSHA256",archive.hash());
            operation="remove_actual_old_rom";Files.move(oldRom,oldRom.resolveSibling("retained-original.dol"));check(!Files.exists(oldRom)&&Hashing.sha256(newRom).equals(hash),"old actually loaded ROM path is absent and differently named new path has identical bytes");
            operation="restore_closed_profile_new_root";NativeProfileArchive.restore(coldProfile,"wii",zipped,()->true);LaunchConfig cold=coldFiles.select(Platform.WII,newRom,new MachineSaveKey(machine,hash)).launch();check(!cold.saveDirectory().equals(profile),"same machine is reopened at a genuinely different native profile root");
            operation="cold_boot_restore";runtime=ProcessConsoleRuntime.open(cold);runtime.loadStateFrom(state);idle(runtime,30);check(gameFrame(coldProfile)==first,"new root keeps the real native save from the closed profile");
            operation="cold_actual_A";press(runtime);check(gameFrame(coldProfile)>first,"restored real CPU A writes a later native game frame in the new root");
            operation="cold_checkpoint";Path next=root.resolve("cold-final.mxs1");runtime.saveStateTo(next);NativeProfileArchive.Snapshot nextProfile=NativeProfileArchive.pack(coldProfile,"wii",root.resolve("cold-final-profile.zip"),()->true,n->{});report.put("coldStateSHA256",Hashing.sha256(next));report.put("coldProfileSHA256",nextProfile.hash());check(nextProfile.expandedBytes()>=128L*1024*1024,"new root checkpoint still includes the full native SD image");
            operation="cold_close";workers.add(closure(runtime));runtime=null;Map<String,String> after=tree(profile);report.put("oldTreeAfter",after);check(before.equals(after),"entire old actual native directory remains byte-for-byte unchanged after cold restore, A, save and shutdown");report.put("newTree",tree(coldProfile));report.put("complete",true);
        }catch(Throwable failure){report.put("failedOperation",operation);report.put("failure",TransferFailure.diagnostic(failure));throw failure;}
        finally{
            if(runtime!=null)try{workers.add(closure(runtime));}catch(Exception failure){report.put("cleanupFailure",TransferFailure.diagnostic(failure));}
            if(before!=null){Map<String,String> after=tree(profile);report.put("oldTreeAfter",after);report.put("oldActualTreeUnchanged",before.equals(after));}
            report.put("oldActualRomAbsent",!Files.exists(oldRom));report.put("checks",checks);Files.writeString(root.resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        }
    }
}
