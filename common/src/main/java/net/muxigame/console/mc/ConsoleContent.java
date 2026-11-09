package net.muxigame.console.mc;

import net.muxigame.console.MuxiConsole;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

public final class ConsoleContent {
    public static final DeferredRegister<Block> BLOCKS=DeferredRegister.create(Registries.BLOCK,MuxiConsole.ID);
    public static final DeferredRegister<Item> ITEMS=DeferredRegister.create(Registries.ITEM,MuxiConsole.ID);
    public static final DeferredRegister<BlockEntityType<?>> ENTITIES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,MuxiConsole.ID);
    public static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS=DeferredRegister.create(Registries.MENU,MuxiConsole.ID);
    public static final DeferredRegister<net.minecraft.world.item.crafting.RecipeSerializer<?>> RECIPES=DeferredRegister.create(Registries.RECIPE_SERIALIZER,MuxiConsole.ID);
    public static final Supplier<net.minecraft.world.item.crafting.RecipeSerializer<?>> HARDWARE_RECOLOR=RECIPES.register("hardware_recolor",()->net.muxigame.console.mc.recipe.HardwareRecolorRecipe.SERIALIZER);
    public static final Supplier<net.minecraft.world.inventory.MenuType<WriterMenu>> WRITER_MENU=MENUS.register("cartridge_writer",()->net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(WriterMenu::new));
    public static final Supplier<Block> CASE=BLOCKS.register("cartridge_case",()->new CartridgeCaseBlock(BlockBehaviour.Properties.of().strength(1.0f).noOcclusion().pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)));
    public static final Supplier<BlockEntityType<CartridgeCaseEntity>> CASE_ENTITY=ENTITIES.register("cartridge_case",()->BlockEntityType.Builder.of(CartridgeCaseEntity::new,CASE.get()).build(null));
    public static final Supplier<net.minecraft.world.inventory.MenuType<CartridgeCaseMenu>> CASE_MENU=MENUS.register("cartridge_case",()->net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create((id,inventory,data)->new CartridgeCaseMenu(id,inventory,data.readVarInt())));
    public static final Supplier<Item> CASE_ITEM=ITEMS.register("cartridge_case",()->new CartridgeCaseItem(CASE.get(),new Item.Properties().stacksTo(1)));
    public static final Supplier<Block> DESK=BLOCKS.register("desk_console",()->new DeviceBlock(DeviceBlock.Kind.DESK,BlockBehaviour.Properties.of().strength(1.4f).noOcclusion()));
    public static final Supplier<Block> WRITER=BLOCKS.register("cartridge_writer",()->new DeviceBlock(DeviceBlock.Kind.WRITER,BlockBehaviour.Properties.of().strength(1.4f).noOcclusion()));
    public static final Supplier<Block> SCREEN=BLOCKS.register("screen",()->new ScreenBlock(BlockBehaviour.Properties.of().strength(1.2f).noOcclusion().pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)));
    public static final Supplier<Block> DOCK=BLOCKS.register("handheld_dock",()->new DeviceBlock(DeviceBlock.Kind.DOCK,BlockBehaviour.Properties.of().strength(1.4f).noOcclusion().pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)));
    public static final Supplier<BlockEntityType<DeviceEntity>> DEVICE=ENTITIES.register("device",()->BlockEntityType.Builder.of(DeviceEntity::new,DESK.get(),WRITER.get(),SCREEN.get(),DOCK.get()).build(null));
    public static final Supplier<Item> DESK_ITEM=ITEMS.register("desk_console",()->new HardwareBlockItem(DESK.get(),new Item.Properties().stacksTo(1)));
    public static final Supplier<Item> WRITER_ITEM=ITEMS.register("cartridge_writer",()->new BlockItem(WRITER.get(),new Item.Properties()));
    public static final Supplier<Item> SCREEN_ITEM=ITEMS.register("screen",()->new ScreenBlockItem(SCREEN.get(),new Item.Properties()));
    public static final Supplier<Item> DOCK_ITEM=ITEMS.register("handheld_dock",()->new BlockItem(DOCK.get(),new Item.Properties()));
    public static final Supplier<Item> BLANK=ITEMS.register("blank_cartridge",()->new Item(new Item.Properties()));
    public static final Supplier<Item> CARTRIDGE=ITEMS.register("cartridge",()->new Item(new Item.Properties().stacksTo(1)));
    public static final Supplier<Item> CONTROLLER=ITEMS.register("controller",()->new Item(new Item.Properties().stacksTo(1)));
    public static final Supplier<Item> HANDHELD=ITEMS.register("handheld",()->new HandheldItem(new Item.Properties().stacksTo(1)));
    public static void register(IEventBus bus){BLOCKS.register(bus);ITEMS.register(bus);ENTITIES.register(bus);MENUS.register(bus);RECIPES.register(bus);bus.addListener(ConsoleContent::creative);}
    private static void creative(net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event){
        if(event.getTabKey().location().equals(net.minecraft.resources.ResourceLocation.withDefaultNamespace("tools_and_utilities"))){for(var s:new Supplier[]{DESK_ITEM,WRITER_ITEM,SCREEN_ITEM,DOCK_ITEM,CASE_ITEM,BLANK,CONTROLLER,HANDHELD})event.accept((Item)s.get());}
        if(event.getTabKey().location().equals(net.minecraft.resources.ResourceLocation.withDefaultNamespace("tools_and_utilities")))for(var body:HardwareCatalog.bodies())event.accept(HardwareCatalog.variant(new ItemStack(body.handheld()?HANDHELD.get():DESK_ITEM.get()),body.id()));
    }
    private ConsoleContent(){}
}
