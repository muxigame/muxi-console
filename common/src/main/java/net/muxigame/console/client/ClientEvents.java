package net.muxigame.console.client;

import net.muxigame.console.MuxiConsole;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(modid=MuxiConsole.ID,value=Dist.CLIENT)
public final class ClientEvents {
    /** A gameplay Screen owns keyboard/mouse actions; stale world mappings cannot survive into the next tick. */
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void capturedTick(ClientTickEvent.Pre event){
        if(net.minecraft.client.Minecraft.getInstance().screen instanceof net.muxigame.console.client.ui.GameScreen)
            net.minecraft.client.KeyMapping.releaseAll();
    }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void capturedMovement(net.neoforged.neoforge.client.event.MovementInputUpdateEvent event){
        var mc=net.minecraft.client.Minecraft.getInstance();
        if(mc.screen instanceof net.muxigame.console.client.ui.GameScreen&&event.getEntity()==mc.player){
            var input=event.getInput();input.forwardImpulse=0;input.leftImpulse=0;
            input.up=false;input.down=false;input.left=false;input.right=false;input.jumping=false;input.shiftKeyDown=false;
        }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){ConsoleClient.INSTANCE.tick();}
    @SubscribeEvent public static void key(net.neoforged.neoforge.client.event.InputEvent.Key event){
        var mc=net.minecraft.client.Minecraft.getInstance();
        if(event.getKey()!=net.muxigame.console.client.input.InputProfiles.get().focusKey()||event.getAction()!=org.lwjgl.glfw.GLFW.GLFW_PRESS||mc.screen!=null||!ConsoleClient.INSTANCE.heldWorldPlaying())return;
        for(var mapping:mc.options.keyMappings)if(mapping.matches(event.getKey(),event.getScanCode())){mapping.setDown(false);while(mapping.consumeClick()){} }
        ConsoleClient.INSTANCE.openHeldControls();
    }
    @SubscribeEvent public static void opening(net.neoforged.neoforge.client.event.ScreenEvent.Opening event){
        if(event.getCurrentScreen()==null&&event.getNewScreen() instanceof net.minecraft.client.gui.screens.PauseScreen&&ConsoleClient.INSTANCE.leaveHeldWorld())event.setCanceled(true);
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){ConsoleClient.INSTANCE.stop();}
}
