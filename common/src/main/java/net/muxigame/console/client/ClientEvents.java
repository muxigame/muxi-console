package net.muxigame.console.client;

import net.muxigame.console.MuxiConsole;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(modid=MuxiConsole.ID,value=Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){ConsoleClient.INSTANCE.tick();}
    @SubscribeEvent public static void key(net.neoforged.neoforge.client.event.InputEvent.Key event){
        var mc=net.minecraft.client.Minecraft.getInstance();
        if(event.getKey()!=org.lwjgl.glfw.GLFW.GLFW_KEY_F||event.getAction()!=org.lwjgl.glfw.GLFW.GLFW_PRESS||event.getModifiers()!=0||mc.screen!=null||!ConsoleClient.INSTANCE.heldWorldPlaying())return;
        for(var mapping:mc.options.keyMappings)if(mapping.matches(event.getKey(),event.getScanCode())){mapping.setDown(false);while(mapping.consumeClick()){} }
        ConsoleClient.INSTANCE.openHeldControls();
    }
    @SubscribeEvent public static void opening(net.neoforged.neoforge.client.event.ScreenEvent.Opening event){
        if(event.getCurrentScreen()==null&&event.getNewScreen() instanceof net.minecraft.client.gui.screens.PauseScreen&&ConsoleClient.INSTANCE.leaveHeldWorld())event.setCanceled(true);
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){ConsoleClient.INSTANCE.stop();}
}
