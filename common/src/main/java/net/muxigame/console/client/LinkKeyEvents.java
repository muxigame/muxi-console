package net.muxigame.console.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.muxigame.console.MuxiConsole;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid=MuxiConsole.ID,value=Dist.CLIENT)
public final class LinkKeyEvents {
    private static final class Mapping extends KeyMapping {
        Mapping(){super("key.muxi_console.link",KeyConflictContext.IN_GAME,InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_F9,"key.categories.muxi_console.link");}
        private static InputConstants.Key allowed(InputConstants.Key key){return key.getType()==InputConstants.Type.KEYSYM&&key.getValue()==GLFW.GLFW_KEY_ESCAPE||key.getType()==InputConstants.Type.MOUSE&&key.getValue()==GLFW.GLFW_MOUSE_BUTTON_RIGHT?InputConstants.UNKNOWN:key;}
        @Override public void setKey(InputConstants.Key key){super.setKey(allowed(key));}
        @Override public void setKeyModifierAndCode(KeyModifier modifier,InputConstants.Key key){super.setKeyModifierAndCode(modifier,allowed(key));}
    }
    private static final KeyMapping KEY=new Mapping();
    public static KeyMapping mapping(){return KEY;}
    public static boolean owns(KeyMapping mapping){return mapping==KEY;}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){var mc=Minecraft.getInstance();while(KEY.consumeClick())if(mc.player!=null&&mc.screen==null)LinkPlayClient.INSTANCE.open();}
    private static boolean modifier(){return switch(KEY.getKeyModifier()){case CONTROL->net.minecraft.client.gui.screens.Screen.hasControlDown();case SHIFT->net.minecraft.client.gui.screens.Screen.hasShiftDown();case ALT->net.minecraft.client.gui.screens.Screen.hasAltDown();default->true;};}
    @SubscribeEvent public static void screenKey(ScreenEvent.KeyPressed.Pre event){if(event.getScreen() instanceof net.muxigame.console.client.ui.GameScreen&&KEY.matches(event.getKeyCode(),event.getScanCode())&&modifier()){LinkPlayClient.INSTANCE.open();event.setCanceled(true);}}
    @SubscribeEvent public static void screenMouse(ScreenEvent.MouseButtonPressed.Pre event){if(event.getScreen() instanceof net.muxigame.console.client.ui.GameScreen&&KEY.matchesMouse(event.getButton())&&modifier()){LinkPlayClient.INSTANCE.open();event.setCanceled(true);}}
    @SubscribeEvent public static void controls(ScreenEvent.Init.Post event){if(!(event.getScreen() instanceof net.minecraft.client.gui.screens.options.controls.ControlsScreen screen))return;for(var child:screen.children())if(child instanceof net.minecraft.client.gui.components.OptionsList list){list.addSmall(java.util.List.of(net.minecraft.client.gui.components.Button.builder(net.minecraft.network.chat.Component.translatable("key.muxi_console.link"),button->LinkPlayClient.INSTANCE.open()).width(310).build()));break;}}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){LinkPlayClient.INSTANCE.stop();}
    @EventBusSubscriber(modid=MuxiConsole.ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Registration {
        @SubscribeEvent public static void register(RegisterKeyMappingsEvent event){event.register(KEY);}
    }
    private LinkKeyEvents(){}
}
