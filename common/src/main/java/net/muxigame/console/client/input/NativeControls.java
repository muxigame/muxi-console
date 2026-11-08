package net.muxigame.console.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.Tooltip;
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
    private static final Set<InputConstants.Key> DOWN=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static boolean initialized;
    private static Platform selectedPlatform=Platform.NES;
    private static final String[] PAD_NAMES={"A / Cross","B / Circle","X / Square","Y / Triangle","L1","R1","Back","Start","Guide","L3","R3","D-pad Up","D-pad Right","D-pad Down","D-pad Left","L2","R2"};

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
        }
    }
    private static String name(Platform platform,int index){return "key.muxi_console."+platform.id()+(index<16?".button.":".axis.")+(index<16?index:index-16);}
    public static KeyMapping key(Platform platform,int bit){if(bit<0||bit>=16)throw new IllegalArgumentException("Button");return row(platform)[bit];}
    public static KeyMapping axis(Platform platform,int direction){if(direction<0||direction>=8)throw new IllegalArgumentException("Axis direction");return row(platform)[direction+16];}
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
    public static void clear(){DOWN.clear();}
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
        return new PortInput(mask,axes[0],axes[1],axes[2],axes[3]);
    }
    public static void openSettings(Screen parent){clear();Minecraft mc=Minecraft.getInstance();mc.setScreen(new net.minecraft.client.gui.screens.options.controls.ControlsScreen(parent,mc.options));}

    /** The standard Controls list also owns controller selection/mapping; no separate mod binding screen. */
    @SubscribeEvent public static void options(ScreenEvent.Init.Post event){
        if(!(event.getScreen() instanceof net.minecraft.client.gui.screens.options.controls.ControlsScreen screen))return;
        OptionsList list=null;for(var child:screen.children())if(child instanceof OptionsList found){list=found;break;}if(list==null)return;
        var profiles=InputProfiles.get();var profile=profiles.profile(selectedPlatform);
        if(profile.enabled()&&profile.gamepadGuid().isEmpty()){profile.setGamepad("*",-1);saveController();}
        var devices=InputProfiles.availableGamepads();List<String> choices=new ArrayList<>(List.of("","*"));
        for(var device:devices)if(!choices.contains(device.guid()))choices.add(device.guid());
        if(!profile.gamepadGuid().isEmpty()&&!choices.contains(profile.gamepadGuid()))choices.add(profile.gamepadGuid());
        String choice=!profile.enabled()?"":profile.gamepadGuid().isEmpty()?"*":profile.gamepadGuid();
        var platform=CycleButton.<Platform>builder(p->Component.translatable("platform.muxi_console."+p.id())).withValues(Platform.values()).withInitialValue(selectedPlatform)
                .create(0,0,150,20,Component.translatable("options.muxi_console.platform"),(button,value)->{selectedPlatform=value;screen.init(Minecraft.getInstance(),screen.width,screen.height);});
        var device=CycleButton.<String>builder(value->{if(value.isEmpty())return Component.translatable("options.off");if(value.equals("*"))return Component.translatable("options.muxi_console.automatic");for(var d:devices)if(d.guid().equals(value))return Component.literal(d.name());return Component.translatable("options.muxi_console.disconnected");}).withValues(choices).withInitialValue(choice)
                .create(0,0,150,20,Component.translatable("options.muxi_console.gamepad"),(button,value)->{profiles.setEnabled(selectedPlatform,!value.isEmpty());profiles.profile(selectedPlatform).setGamepad(value,-1);saveController();});
        platform.setTooltip(Tooltip.create(Component.translatable("options.muxi_console.reserved")));list.addSmall(platform,device);
        List<Integer> buttons=new ArrayList<>();for(int i=-1;i<=16;i++)buttons.add(i);
        for(int bit=0;bit<16;bit+=2){var widgets=new ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
            for(int offset=0;offset<2;offset++){final int logical=bit+offset;
                widgets.add(CycleButton.<Integer>builder(value->value<0?Component.translatable("key.keyboard.unknown"):Component.literal(PAD_NAMES[value])).withValues(buttons).withInitialValue(profile.gamepadButton(logical))
                        .create(0,0,150,20,Component.translatable("options.muxi_console.gamepad_button",Component.translatable(name(selectedPlatform,logical))),(button,value)->{profiles.bindGamepad(selectedPlatform,logical,value);saveController();screen.init(Minecraft.getInstance(),screen.width,screen.height);}));
            }list.addSmall(widgets);
        }
    }
    private static void saveController(){try{InputProfiles.get().save();}catch(IOException error){MuxiConsole.LOG.warn("Could not save console gamepad options",error);}}
    private NativeControls(){}
}
