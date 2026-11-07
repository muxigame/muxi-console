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
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){ConsoleClient.INSTANCE.stop();}
}
