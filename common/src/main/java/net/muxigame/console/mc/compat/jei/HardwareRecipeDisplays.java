package net.muxigame.console.mc.compat.jei;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.mc.ConsoleContent;
import net.muxigame.console.mc.HardwareCatalog;
import net.muxigame.console.mc.recipe.HardwareRecoloring;
import net.neoforged.neoforge.common.crafting.CompoundIngredient;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;
import net.neoforged.neoforge.common.crafting.DifferenceIngredient;

/** Identity-free recipe-browser examples. These are never installed in the world's recipe manager. */
public final class HardwareRecipeDisplays {
    private record Color(DyeColor dye,boolean lens){}

    public static List<ItemStack> variants(){
        return HardwareCatalog.bodies().stream().map(body->stack(body.handheld(),body.id())).toList();
    }

    public static List<ItemStack> ingredients(){
        List<ItemStack> items=new ArrayList<>();
        items.add(stack(true,0));items.add(stack(false,0));items.addAll(variants());
        return List.copyOf(items);
    }

    /** One display per existing body, including every other body of the same physical item as a reshape input. */
    public static List<RecipeHolder<CraftingRecipe>> recipes(){
        List<RecipeHolder<CraftingRecipe>> recipes=new ArrayList<>();
        for(var body:HardwareCatalog.bodies()){
            Color color=color(body);
            NonNullList<Ingredient> inputs=NonNullList.create();
            inputs.add(sources(body));inputs.add(Ingredient.of(DyeItem.byColor(color.dye())));
            if(color.lens())inputs.add(Ingredient.of(Items.GLASS_PANE));
            CraftingRecipe recipe=new ShapelessRecipe("muxi_console.hardware",CraftingBookCategory.MISC,stack(body.handheld(),body.id()),inputs);
            recipes.add(new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath(MuxiConsole.ID,"jei/hardware/"+body.id()),recipe));
        }
        return List.copyOf(recipes);
    }

    private static Ingredient sources(HardwareCatalog.Body target){
        List<Ingredient> alternatives=new ArrayList<>();
        List<Ingredient> shaped=new ArrayList<>();
        for(var source:HardwareCatalog.bodies())if(source.handheld()==target.handheld()){
            // Only the model matters here. Names, machine identity and inserted media are not recipe-browser subtypes.
            Ingredient model=DataComponentIngredient.of(false,DataComponents.CUSTOM_MODEL_DATA,new CustomModelData(source.id()),
                target.handheld()?ConsoleContent.HANDHELD.get():ConsoleContent.DESK_ITEM.get());
            shaped.add(model);if(source.id()!=target.id())alternatives.add(model);
        }
        // Subtract known shaped models rather than requiring an otherwise pristine core: old cores can carry
        // a name, machine UUID or media. The display stays one core example and never absorbs the CMD examples.
        // Unknown/forged CMDs remain subject to the real recipe's admission, not this client-only display.
        Ingredient core=DifferenceIngredient.of(Ingredient.of(stack(target.handheld(),0)),CompoundIngredient.of(shaped.toArray(Ingredient[]::new)));
        alternatives.addFirst(core);
        return CompoundIngredient.of(alternatives.toArray(Ingredient[]::new));
    }

    private static Color color(HardwareCatalog.Body body){
        Color found=null;
        for(DyeColor dye:DyeColor.values())for(boolean lens:new boolean[]{false,true}){
            if(HardwareRecoloring.target(body.handheld(),dye,lens)!=body.id())continue;
            if(found!=null)throw new IllegalStateException("Ambiguous hardware dye: "+body.id());
            found=new Color(dye,lens);
        }
        if(found==null)throw new IllegalStateException("Missing hardware dye: "+body.id());
        return found;
    }

    private static ItemStack stack(boolean handheld,int model){
        return HardwareCatalog.variant(new ItemStack(handheld?ConsoleContent.HANDHELD.get():ConsoleContent.DESK_ITEM.get()),model);
    }

    private HardwareRecipeDisplays(){}
}
