package net.muxigame.consoleqa;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/** Read-only observer. It adds no ingredient, recipe, subtype, transfer handler or visibility rule. */
@JeiPlugin
public final class RecipeProbePlugin implements IModPlugin {
    static volatile IJeiRuntime runtime;
    @Override public ResourceLocation getPluginUid(){return ResourceLocation.fromNamespaceAndPath("muxi_console_qa_client","recipe_observer");}
    @Override public void onRuntimeAvailable(IJeiRuntime value){runtime=value;}
    @Override public void onRuntimeUnavailable(){runtime=null;}
}
