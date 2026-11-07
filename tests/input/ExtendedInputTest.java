package net.muxigame.console.tests.input;

import java.nio.file.*;
import java.util.*;
import net.muxigame.console.client.input.InputProfiles;
import net.muxigame.console.domain.Platform;
import org.lwjgl.glfw.GLFW;

public final class ExtendedInputTest {
    private static int checks;
    private static void check(boolean value,String description){checks++;if(!value)throw new AssertionError(description);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory(Path.of(args[0]).toAbsolutePath().getParent(),"extended-input-");
        try{
            InputProfiles profiles=new InputProfiles(root.toFile());var n64=profiles.profile(Platform.N64);var md=profiles.profile(Platform.MD);
            check(n64.keyBinding(0)==GLFW.GLFW_KEY_J&&n64.keyBinding(4)==GLFW.GLFW_KEY_UP,"N64 A and digital directions match hardware");
            check(n64.axisBinding(0)==GLFW.GLFW_KEY_A&&n64.axisBinding(2)==GLFW.GLFW_KEY_W,"N64 keyboard can drive analog stick by default");
            check(md.keyBinding(0)==GLFW.GLFW_KEY_J&&md.keyBinding(11)==GLFW.GLFW_KEY_L,"MD A/C use actual core buttons");
            check(InputProfiles.buttonName(Platform.FDS,10).contains("换面")&&InputProfiles.buttonName(Platform.FDS,11).contains("插入"),"FDS disk buttons are discoverable");
            profiles.bindAxis(Platform.N64,0,GLFW.GLFW_KEY_J);check(n64.keyBinding(0)==-1&&n64.axisBinding(0)==GLFW.GLFW_KEY_J,"One physical key cannot steer and fire simultaneously");
            profiles.bind(Platform.N64,8,GLFW.GLFW_KEY_J);check(n64.axisBinding(0)==-1&&n64.keyBinding(8)==GLFW.GLFW_KEY_J,"Key assignment clears old analog action");
            for(var platform:Platform.values()){
                int key=GLFW.GLFW_KEY_F10;profiles.bindAxis(platform,7,key);profiles.bindGamepad(platform,8,15);profiles.bindGamepad(platform,0,16);
                check(profiles.profile(platform).gamepadButton(12)==-1&&profiles.profile(platform).gamepadButton(13)==-1,"Remapped triggers no longer fire prior action");
                for(int reserved:new int[]{GLFW.GLFW_KEY_ESCAPE,GLFW.GLFW_KEY_F,GLFW.GLFW_KEY_F8})try{profiles.bindAxis(platform,0,reserved);throw new AssertionError("Reserved key accepted");}catch(IllegalArgumentException expected){checks++;}
            }
            profiles.save();InputProfiles loaded=new InputProfiles(root.toFile());check(loaded.loadWarning().isEmpty(),"Extended configuration loads atomically");
            for(var platform:Platform.values()){for(int bit=0;bit<16;bit++)check(loaded.profile(platform).gamepadButton(bit)==profiles.profile(platform).gamepadButton(bit),"Gamepad mapping persisted");for(int direction=0;direction<8;direction++)check(loaded.profile(platform).axisBinding(direction)==profiles.profile(platform).axisBinding(direction),"Keyboard analog mapping persisted");}
            loaded.bindGamepad(Platform.N64,8,3);check(loaded.profile(Platform.GBA).gamepadButton(8)==15,"Independent platform controller mapping");
            loaded.reset(Platform.N64);check(loaded.profile(Platform.N64).axisBinding(0)==GLFW.GLFW_KEY_A&&loaded.profile(Platform.N64).gamepadButton(12)==15,"Reset restores entire hardware profile");
            System.out.println("Extended input: "+checks+" checks passed");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
