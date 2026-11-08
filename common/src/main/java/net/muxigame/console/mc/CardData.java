package net.muxigame.console.mc;

import java.util.Optional;
import java.util.UUID;
import java.util.List;
import net.muxigame.console.domain.Cartridge;
import net.muxigame.console.domain.Platform;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.network.chat.Component;

/** Metadata belongs to the physical item instance, never to the uploader account. */
public final class CardData {
    public static Optional<Cartridge> read(ItemStack stack){
        if(stack.is(ConsoleContent.HANDHELD.get())&&stack.has(DataComponents.CONTAINER))return actualCard(stack).flatMap(CardData::read);
        return metadata(stack);
    }
    private static Optional<Cartridge> metadata(ItemStack stack){
        try{
            if(!stack.is(ConsoleContent.CARTRIDGE.get()) && !stack.is(ConsoleContent.HANDHELD.get()))return Optional.empty();
            CompoundTag t=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getCompound("muxi_console");
            if(!t.hasUUID("instance") || !t.getString("hash").matches("[a-f0-9]{64}"))return Optional.empty();
            return Optional.of(new Cartridge(t.getUUID("instance"),Platform.fromId(t.getString("platform")),t.getString("hash"),t.getLong("bytes"),t.getString("title")));
        }catch(RuntimeException invalid){return Optional.empty();}
    }
    private static Optional<ItemStack> actualCard(ItemStack handheld){
        var contents=handheld.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).nonEmptyStream().limit(2).toList();
        if(contents.size()!=1)return Optional.empty();ItemStack card=contents.getFirst();
        return card.is(ConsoleContent.CARTRIDGE.get())&&card.getCount()==1&&metadata(card).isPresent()?Optional.of(card):Optional.empty();
    }
    /** Read-only actual cartridge copy. Old metadata-only shells retain their original UUID, never a new identity. */
    public static ItemStack inserted(ItemStack handheld){
        if(!handheld.is(ConsoleContent.HANDHELD.get()))return ItemStack.EMPTY;
        if(handheld.has(DataComponents.CONTAINER))return actualCard(handheld).map(ItemStack::copy).orElse(ItemStack.EMPTY);
        return metadata(handheld).map(CardData::create).orElse(ItemStack.EMPTY);
    }
    public static boolean hasInserted(ItemStack handheld){
        return handheld.is(ConsoleContent.HANDHELD.get())&&(handheld.has(DataComponents.CONTAINER)?handheld.get(DataComponents.CONTAINER).nonEmptyStream().findAny().isPresent():metadata(handheld).isPresent());
    }
    /** Copies the full physical card; callers transfer exactly one source item only after checking authority and capacity. */
    public static void install(ItemStack handheld,ItemStack card){
        if(!handheld.is(ConsoleContent.HANDHELD.get())||handheld.getCount()!=1||hasInserted(handheld)||!card.is(ConsoleContent.CARTRIDGE.get())||card.getCount()!=1||metadata(card).isEmpty())throw new IllegalArgumentException("Invalid occupied or non-cartridge handheld slot");
        ItemContainerContents contents=ItemContainerContents.fromItems(List.of(card.copy()));
        removeMetadata(handheld);handheld.set(DataComponents.CONTAINER,contents);
    }
    public static ItemStack create(Cartridge card){ItemStack s=new ItemStack(ConsoleContent.CARTRIDGE.get());write(s,card);return s;}
    public static void write(ItemStack stack,Cartridge card){
        if(stack.is(ConsoleContent.HANDHELD.get())){install(stack,create(card));return;}
        CompoundTag root=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag(),t=new CompoundTag();
        t.putUUID("instance",card.instanceId());t.putString("platform",card.platform().id());t.putString("hash",card.sha256());t.putLong("bytes",card.size());t.putString("title",card.title());root.put("muxi_console",t);
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(root));
        if(stack.is(ConsoleContent.CARTRIDGE.get())){stack.set(DataComponents.CUSTOM_MODEL_DATA,new CustomModelData(card.platform().customModelData()));stack.set(DataComponents.CUSTOM_NAME,Component.literal(card.title()+" · "+card.platform().id().toUpperCase(java.util.Locale.ROOT)));}
    }
    private static void removeMetadata(ItemStack stack){CompoundTag root=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();root.remove("muxi_console");if(root.isEmpty())stack.remove(DataComponents.CUSTOM_DATA);else stack.set(DataComponents.CUSTOM_DATA,CustomData.of(root));}
    public static void clear(ItemStack stack){
        if(stack.is(ConsoleContent.HANDHELD.get())){
            if(stack.has(DataComponents.CONTAINER)&&stack.get(DataComponents.CONTAINER).nonEmptyStream().findAny().isPresent()&&actualCard(stack).isEmpty())throw new IllegalArgumentException("Invalid handheld contents must not be discarded");
            stack.remove(DataComponents.CONTAINER);
        }
        removeMetadata(stack);if(stack.is(ConsoleContent.CARTRIDGE.get())){stack.remove(DataComponents.CUSTOM_MODEL_DATA);stack.remove(DataComponents.CUSTOM_NAME);}
    }
    private CardData(){}
}
