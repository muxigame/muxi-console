package net.muxigame.console.mc.recipe;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.mc.CardData;
import net.muxigame.console.mc.ConsoleContent;
import net.muxigame.console.mc.HardwareCatalog;

/** A normal crafting transfer of one physical device. It never allocates a machine/card identity or touches a save. */
public final class HardwareRecolorRecipe extends CustomRecipe {
    public static final ResourceLocation TYPE_ID=ResourceLocation.fromNamespaceAndPath(MuxiConsole.ID,"hardware_recolor");
    public static final RecipeSerializer<HardwareRecolorRecipe> SERIALIZER=new SimpleCraftingRecipeSerializer<>(HardwareRecolorRecipe::new);
    private record Plan(ItemStack source,int model){}
    public HardwareRecolorRecipe(CraftingBookCategory category){super(category);}
    private static Plan plan(CraftingInput input){
        ItemStack machine=ItemStack.EMPTY;DyeColor dye=null;boolean lens=false;
        for(int i=0;i<input.size();i++){
            ItemStack item=input.getItem(i);if(item.isEmpty())continue;
            if(item.is(ConsoleContent.HANDHELD.get())||item.is(ConsoleContent.DESK_ITEM.get())){
                if(!machine.isEmpty()||item.getCount()!=1)return null;machine=item;
            }else if(item.getItem() instanceof DyeItem color){if(dye!=null)return null;dye=color.getDyeColor();}
            else if(item.is(Items.GLASS_PANE)){if(lens)return null;lens=true;}
            else return null;
        }
        if(machine.isEmpty()||dye==null)return null;
        boolean handheld=machine.is(ConsoleContent.HANDHELD.get());
        int target=HardwareRecoloring.target(handheld,dye,lens),current=HardwareCatalog.model(machine);
        var body=HardwareCatalog.body(target);
        if(target==0||target==current||body==null||body.handheld()!=handheld||current<0||current>25)return null;
        boolean legacyTag=machine.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().contains("muxi_console");
        if(machine.has(DataComponents.CONTAINER)){
            if(legacyTag)return null;
            var contents=machine.get(DataComponents.CONTAINER);
            if(contents.getSlots()>1)return null;
            var cards=contents.nonEmptyStream().limit(2).toList();
            if(!cards.isEmpty()){
                if(!handheld||cards.size()!=1)return null;
                ItemStack card=cards.getFirst();var data=CardData.read(card);
                if(!card.is(ConsoleContent.CARTRIDGE.get())||card.getCount()!=1||data.isEmpty()||!body.accepts(data.get().platform()))return null;
            }
        }else if(handheld){
            var legacy=CardData.read(machine);
            if(legacy.isPresent()){if(!body.accepts(legacy.get().platform()))return null;}
            else if(legacyTag)return null;
        }else if(legacyTag)return null;
        return new Plan(machine,target);
    }
    @Override public boolean matches(CraftingInput input,Level level){return plan(input)!=null;}
    @Override public ItemStack assemble(CraftingInput input,HolderLookup.Provider registries){
        Plan plan=plan(input);if(plan==null)return ItemStack.EMPTY;
        ItemStack result=plan.source().copyWithCount(1);
        // Preserve every component: custom name/lore/data, the machine UUID and the entire real cartridge stack.
        result.set(DataComponents.CUSTOM_MODEL_DATA,new CustomModelData(plan.model()));return result;
    }
    @Override public NonNullList<ItemStack> getRemainingItems(CraftingInput input){return NonNullList.withSize(input.size(),ItemStack.EMPTY);}
    @Override public boolean canCraftInDimensions(int width,int height){return width*height>=2;}
    @Override public RecipeSerializer<?> getSerializer(){return SERIALIZER;}
}
