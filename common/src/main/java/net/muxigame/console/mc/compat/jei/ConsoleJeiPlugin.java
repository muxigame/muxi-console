package net.muxigame.console.mc.compat.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.registration.IExtraIngredientRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.mc.ConsoleContent;
import net.muxigame.console.mc.HardwareCatalog;

/** Loaded by JEI's plugin discovery only; the mod's normal entry points never reference this class. */
@JeiPlugin
public final class ConsoleJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID=ResourceLocation.fromNamespaceAndPath(MuxiConsole.ID,"hardware");
    private static final ISubtypeInterpreter<ItemStack> MODEL=new ISubtypeInterpreter<>(){
        @Override public Object getSubtypeData(ItemStack item,UidContext context){return HardwareCatalog.model(item);}
        @Override public String getLegacyStringSubtypeInfo(ItemStack item,UidContext context){return "model="+HardwareCatalog.model(item);}
    };

    @Override public ResourceLocation getPluginUid(){return UID;}

    @Override public void registerItemSubtypes(ISubtypeRegistration registration){
        registration.registerSubtypeInterpreter(ConsoleContent.HANDHELD.get(),MODEL);
        registration.registerSubtypeInterpreter(ConsoleContent.DESK_ITEM.get(),MODEL);
    }

    @Override public void registerExtraIngredients(IExtraIngredientRegistration registration){
        registration.addExtraItemStacks(HardwareRecipeDisplays.ingredients());
    }

    @Override public void registerRecipes(IRecipeRegistration registration){
        // JEI's vanilla category supplies crafting-table/player-inventory transfer. Actual crafting remains the
        // component-preserving HardwareRecolorRecipe on the server; no display recipe enters RecipeManager.
        registration.addRecipes(RecipeTypes.CRAFTING,HardwareRecipeDisplays.recipes());
        registration.addItemStackInfo(HardwareRecipeDisplays.ingredients(),
            Component.translatable("jei.muxi_console.hardware_core"),
            Component.translatable("jei.muxi_console.hardware_reshape"));
    }
}
