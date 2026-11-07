package net.muxigame.console.client;

import net.muxigame.console.MuxiConsole;
import net.muxigame.console.mc.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid=MuxiConsole.ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class ClientRegistration {
    @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->ClientGate.receiver=ConsoleClient.INSTANCE::receive);}
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event){event.registerBlockEntityRenderer(ConsoleContent.DEVICE.get(),DeviceRenderer::new);}
}
