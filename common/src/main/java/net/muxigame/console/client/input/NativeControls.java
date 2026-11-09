package net.muxigame.console.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.client.ui.GameScreen;
import net.muxigame.console.domain.Platform;
import net.muxigame.console.runtime.PortInput;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/** Vanilla options own keyboard bindings; the captured Screen supplies their ordinary level-state callbacks. */
@EventBusSubscriber(modid=MuxiConsole.ID,value=Dist.CLIENT)
public final class NativeControls {
    private static final EnumMap<Platform,KeyMapping[]> MAPPINGS=new EnumMap<>(Platform.class);
    private static final EnumMap<Platform,KeyMapping[]> AUXILIARY=new EnumMap<>(Platform.class);
    private static final EnumMap<Platform,VirtualConsoleInput> VIRTUAL=new EnumMap<>(Platform.class);
    public enum Extra { TILT_LEFT,TILT_RIGHT,TILT_FORWARD,TILT_BACKWARD,SHAKE,CENTRE,BLOW }
    private static final int[] EXTRA_DEFAULTS={GLFW.GLFW_KEY_KP_4,GLFW.GLFW_KEY_KP_6,GLFW.GLFW_KEY_KP_8,GLFW.GLFW_KEY_KP_2,GLFW.GLFW_KEY_KP_5,GLFW.GLFW_KEY_KP_0,GLFW.GLFW_KEY_M};
    private static final Set<InputConstants.Key> DOWN=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static boolean initialized;

    private record Context(Platform platform) implements IKeyConflictContext {
        @Override public boolean isActive(){return Minecraft.getInstance().screen instanceof GameScreen&&ConsoleClient.INSTANCE.inputFeedback().platform()==platform;}
        @Override public boolean conflicts(IKeyConflictContext other){return other instanceof Context c&&c.platform==platform;}
    }
    private static final class Mapping extends KeyMapping {
        Mapping(String name,Context context,int value,String category){super(name,context,InputConstants.Type.KEYSYM,value,category);}
        @Override public boolean same(KeyMapping other){return other.getKeyConflictContext() instanceof Context theirs&&((Context)getKeyConflictContext()).platform==theirs.platform&&super.same(other);}
        @Override public void setKey(InputConstants.Key key){super.setKey(allowed(key));}
        @Override public void setKeyModifierAndCode(KeyModifier modifier,InputConstants.Key key){super.setKeyModifierAndCode(modifier,allowed(key));}
        private static InputConstants.Key allowed(InputConstants.Key key){return key.getType()==InputConstants.Type.MOUSE&&key.getValue()==GLFW.GLFW_MOUSE_BUTTON_RIGHT||key.getType()==InputConstants.Type.KEYSYM&&key.getValue()==GLFW.GLFW_KEY_ESCAPE?InputConstants.UNKNOWN:key;}
    }
    public static void register(RegisterKeyMappingsEvent event){
        if(!MAPPINGS.isEmpty())throw new IllegalStateException("Console key mappings registered twice");
        for(Platform platform:Platform.values()){
            var defaults=new InputProfiles.Profile(platform);var context=new Context(platform);var row=new KeyMapping[24];
            for(int i=0;i<24;i++){
                row[i]=new Mapping(name(platform,i),context,i<16?defaults.keyBinding(i):defaults.axisBinding(i-16),"key.categories.muxi_console."+platform.id());
                event.register(row[i]);
            }
            MAPPINGS.put(platform,row);
            if(hasExtras(platform)){
                var auxiliary=new KeyMapping[Extra.values().length];
                for(Extra extra:Extra.values()){auxiliary[extra.ordinal()]=new Mapping("key.muxi_console."+platform.id()+".extra."+extra.name().toLowerCase(Locale.ROOT),context,EXTRA_DEFAULTS[extra.ordinal()],"key.categories.muxi_console."+platform.id());event.register(auxiliary[extra.ordinal()]);}
                AUXILIARY.put(platform,auxiliary);
            }
        }
    }
    private static String name(Platform platform,int index){return "key.muxi_console."+platform.id()+(index<16?".button.":".axis.")+(index<16?index:index-16);}
    public static KeyMapping key(Platform platform,int bit){if(bit<0||bit>=16)throw new IllegalArgumentException("Button");return row(platform)[bit];}
    public static KeyMapping axis(Platform platform,int direction){if(direction<0||direction>=8)throw new IllegalArgumentException("Axis direction");return row(platform)[direction+16];}
    public static boolean hasExtras(Platform platform){return platform==Platform.NDS||platform==Platform.N3DS||platform==Platform.WII;}
    public static KeyMapping extra(Platform platform,Extra extra){var keys=AUXILIARY.get(platform);if(keys==null)throw new IllegalArgumentException("This platform has no auxiliary bindings");return keys[extra.ordinal()];}
    public static int bindingCount(Platform platform){return 24+(hasExtras(platform)?Extra.values().length:0);}
    private static KeyMapping[] row(Platform platform){var row=MAPPINGS.get(platform);if(row==null)throw new IllegalStateException("Native console bindings are not registered");return row;}

    /** Run once after Minecraft has loaded its options. Existing native entries always win over legacy JSON. */
    public static void initialize(){
        var mc=Minecraft.getInstance();if(initialized||MAPPINGS.isEmpty()||mc.options==null)return;
        Set<String> stored=new HashSet<>();
        var path=mc.gameDirectory.toPath().resolve("options.txt");
        try{if(Files.exists(path))for(String line:Files.readAllLines(path,StandardCharsets.UTF_8)){int at=line.indexOf(':');if(at>0)stored.add(line.substring(0,at));}}
        catch(IOException error){MuxiConsole.LOG.warn("Could not inspect native input options; keeping loaded bindings",error);initialized=true;return;}
        boolean migrated=false;
        for(var entry:MAPPINGS.entrySet()){
            var profile=InputProfiles.get().profile(entry.getKey());
            for(int i=0;i<24;i++)if(!stored.contains("key_"+entry.getValue()[i].getName())){
                int code=i<16?profile.keyBinding(i):profile.axisBinding(i-16);
                entry.getValue()[i].setKeyModifierAndCode(KeyModifier.NONE,code<0?InputConstants.UNKNOWN:InputConstants.Type.KEYSYM.getOrCreate(code));migrated=true;
            }
        }
        KeyMapping.resetMapping();initialized=true;if(migrated)mc.options.save();
    }
    public static void key(int key,int scan,boolean pressed){InputConstants.Key input=InputConstants.getKey(key,scan);if(pressed)DOWN.add(input);else DOWN.remove(input);}
    public static void mouse(int button,boolean pressed){InputConstants.Key input=InputConstants.Type.MOUSE.getOrCreate(button);if(pressed)DOWN.add(input);else DOWN.remove(input);}
    public static void clear(){DOWN.clear();VIRTUAL.clear();}
    private static boolean pressed(KeyMapping mapping){
        if(mapping.isUnbound()||!DOWN.contains(mapping.getKey()))return false;
        return switch(mapping.getKeyModifier()){
            case CONTROL->symbol(GLFW.GLFW_KEY_LEFT_CONTROL)||symbol(GLFW.GLFW_KEY_RIGHT_CONTROL);
            case SHIFT->symbol(GLFW.GLFW_KEY_LEFT_SHIFT)||symbol(GLFW.GLFW_KEY_RIGHT_SHIFT);
            case ALT->symbol(GLFW.GLFW_KEY_LEFT_ALT)||symbol(GLFW.GLFW_KEY_RIGHT_ALT);
            default->true;
        };
    }
    private static boolean symbol(int key){return DOWN.contains(InputConstants.Type.KEYSYM.getOrCreate(key));}
    public static PortInput sample(Platform platform){
        if(!(Minecraft.getInstance().screen instanceof GameScreen)||ConsoleClient.INSTANCE.inputFeedback().platform()!=platform)return PortInput.IDLE;
        var keys=row(platform);PortInput pad=InputProfiles.get().sampleGamepad(platform);int mask=pad.buttons();
        for(int bit=0;bit<16;bit++)if(pressed(keys[bit]))mask|=1<<bit;
        short[] axes={pad.leftX(),pad.leftY(),pad.rightX(),pad.rightY()};
        for(int i=0;i<4;i++){boolean negative=pressed(keys[16+i*2]),positive=pressed(keys[17+i*2]);if(negative||positive)axes[i]=(short)(negative==positive?0:negative?-32767:32767);}
        if((mask&0x30)==0x30)mask&=~0x30;if((mask&0xc0)==0xc0)mask&=~0xc0;
        if(hasExtras(platform)){
            var extra=AUXILIARY.get(platform);var virtual=VIRTUAL.computeIfAbsent(platform,ignored->new VirtualConsoleInput());
            var sample=virtual.sample(pressed(extra[0]),pressed(extra[1]),pressed(extra[2]),pressed(extra[3]),pressed(extra[4]),pressed(extra[5]),pressed(extra[6]),System.nanoTime());
            return new PortInput(mask,axes[0],axes[1],axes[2],axes[3],InputProfiles.get().profile(platform).controllerDevice(),PortInput.Pointer.IDLE,sample.motion(),sample.microphone());
        }
        return new PortInput(mask,axes[0],axes[1],axes[2],axes[3]);
    }
    public static boolean isConsoleBinding(KeyMapping mapping){return mapping.getName().startsWith("key.muxi_console.")&&mapping.getCategory().startsWith("key.categories.muxi_console.");}
    public static void openSettings(Screen parent){clear();KeyMapping.releaseAll();Minecraft.getInstance().setScreen(new net.muxigame.console.client.ui.ConsoleBindingsScreen(parent));}

    /** Keep the normal Controls page compact; all registered values still belong to options.txt. */
    @SubscribeEvent public static void options(ScreenEvent.Init.Post event){
        if(!(event.getScreen() instanceof net.minecraft.client.gui.screens.options.controls.ControlsScreen screen))return;
        OptionsList list=null;for(var child:screen.children())if(child instanceof OptionsList found){list=found;break;}if(list==null)return;
        list.addSmall(List.of(Button.builder(Component.translatable("ui.muxi_console.bindings"),button->openSettings(screen)).width(310).build()));
    }
    @SubscribeEvent public static void vanillaBindings(ScreenEvent.Opening event){
        if(event.getNewScreen()!=null&&event.getNewScreen().getClass()==net.minecraft.client.gui.screens.options.controls.KeyBindsScreen.class)
            event.setNewScreen(new net.muxigame.console.client.ui.MinecraftBindingsScreen(event.getCurrentScreen(),Minecraft.getInstance().options));
    }
    private NativeControls(){}
}
