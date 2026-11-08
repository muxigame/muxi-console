package net.muxigame.console.client;

import net.muxigame.console.MuxiConsole;
import net.muxigame.console.mc.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;

@EventBusSubscriber(modid=MuxiConsole.ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class ClientRegistration {
    @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->ClientGate.receiver=ConsoleClient.INSTANCE::receive);}
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event){event.registerBlockEntityRenderer(ConsoleContent.DEVICE.get(),DeviceRenderer::new);}
    @SubscribeEvent public static void items(RegisterClientExtensionsEvent event){event.registerItem(new IClientItemExtensions(){private final HandheldRenderer renderer=new HandheldRenderer();@Override public BlockEntityWithoutLevelRenderer getCustomRenderer(){return renderer;}},ConsoleContent.HANDHELD.get(),ConsoleContent.CONTROLLER.get());}
    @SubscribeEvent public static void models(ModelEvent.ModifyBakingResult event){for(String name:new String[]{"handheld","controller"}){var id=ModelResourceLocation.inventory(ResourceLocation.fromNamespaceAndPath(MuxiConsole.ID,name));event.getModels().computeIfPresent(id,(key,model)->HandheldRenderer.wrap(model));}}
}
