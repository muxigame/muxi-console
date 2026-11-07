package net.muxigame.console.compat;

import com.google.gson.*;
import java.nio.file.*;
import java.util.Comparator;
import net.muxigame.console.client.input.InputProfiles;
import net.muxigame.console.domain.Platform;

/** Existing user profiles keep their other settings when F8 becomes a browser-selection shortcut. */
public final class BrowserInputMigrationChecks {
    private static int checks;
    private static void check(boolean result,String label){checks++;if(!result)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toAbsolutePath().normalize();Files.createDirectories(root);Path temporary=Files.createTempDirectory(root,"browser-input-");
        try{InputProfiles before=new InputProfiles(temporary.toFile());before.bind(Platform.GBA,0,294);before.setEnabled(Platform.GBA,true);before.profile(Platform.GBA).setGamepad("existing-controller",3);before.save();
            JsonObject file=JsonParser.parseString(Files.readString(before.path())).getAsJsonObject();file.getAsJsonObject("platforms").getAsJsonObject("nes").getAsJsonArray("keys").set(0,new JsonPrimitive(297));Files.writeString(before.path(),file.toString());
            InputProfiles migrated=new InputProfiles(temporary.toFile());check(migrated.profile(Platform.NES).keyBinding(0)==-1,"former F8 binding alone is cleared");check(!migrated.loadWarning().isEmpty(),"migration explains the changed shortcut");check(migrated.profile(Platform.NES).keyBinding(8)==74,"other NES keys preserved");check(migrated.profile(Platform.GBA).keyBinding(0)==294,"other platform custom key preserved");check(migrated.profile(Platform.GBA).enabled(),"gamepad enable preserved");check(migrated.profile(Platform.GBA).gamepadGuid().equals("existing-controller")&&migrated.profile(Platform.GBA).preferredJoystick()==3,"gamepad identity preserved");
            try{migrated.bind(Platform.NES,0,297);throw new AssertionError("Reserved F8 accepted");}catch(IllegalArgumentException expected){checks++;}
            migrated.save();InputProfiles reloaded=new InputProfiles(temporary.toFile());check(reloaded.loadWarning().isEmpty(),"saved migration reloads normally");check(reloaded.profile(Platform.NES).keyBinding(0)==-1&&reloaded.profile(Platform.GBA).keyBinding(0)==294,"migration and other profile persist");
        }finally{try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        check(!Files.exists(temporary),"owned temporary configuration removed");System.out.println("BrowserInputMigrationChecks: "+checks+" passed");
    }
}
