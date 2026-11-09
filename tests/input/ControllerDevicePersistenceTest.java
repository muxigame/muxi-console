package net.muxigame.console.tests.input;

import java.nio.file.*;
import java.util.Comparator;
import net.muxigame.console.client.input.InputProfiles;
import net.muxigame.console.domain.Platform;

/** New controller choices coexist with original keys and GUID profiles across actual disk reloads. */
public final class ControllerDevicePersistenceTest {
    public static void main(String[] args)throws Exception{
        Path parent=Path.of(args[0]).toAbsolutePath();Files.createDirectories(parent);Path root=Files.createTempDirectory(parent,"controller-device-");int checks=0;
        try{
            var original=new InputProfiles(root.toFile());original.bind(Platform.GB,8,80);original.profile(Platform.NDS).setGamepad("example-controller-guid",-1);
            for(int device:new int[]{1,513,769,1025,1793,2305}){
                original.profile(Platform.WII).setControllerDevice(device);original.save();var loaded=new InputProfiles(root.toFile());
                if(loaded.profile(Platform.WII).controllerDevice()!=device||loaded.profile(Platform.GB).keyBinding(8)!=80||!loaded.profile(Platform.NDS).gamepadGuid().equals("example-controller-guid"))throw new AssertionError("Device option overwrote independent stored settings");checks+=3;
            }
            try{original.profile(Platform.WII).setControllerDevice(999);throw new AssertionError("Unknown device accepted");}catch(IllegalArgumentException expected){checks++;}
            String json=Files.readString(original.path());Files.writeString(original.path(),json.replaceAll(",?\\s*\"controllerDevice\"\\s*:\\s*[0-9]+",""));
            var legacy=new InputProfiles(root.toFile());if(legacy.profile(Platform.WII).controllerDevice()!=1||legacy.profile(Platform.GB).keyBinding(8)!=80)throw new AssertionError("Older profiles must retain keys and default to ordinary device1");checks+=2;
        }finally{try(var files=Files.walk(root)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("ControllerDevicePersistenceTest: "+checks+" checks passed");
    }
}
