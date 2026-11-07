package net.muxigame.console.mc;

import java.util.Optional;
import java.util.UUID;
import net.muxigame.console.domain.Cartridge;
import net.muxigame.console.domain.Platform;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.network.chat.Component;

/** Metadata belongs to the physical item instance, never to the uploader account. */
public final class CardData {
    public static Optional<Cartridge> read(ItemStack stack){
        try{
            if(!stack.is(ConsoleContent.CARTRIDGE.get()) && !stack.is(ConsoleContent.HANDHELD.get()))return Optional.empty();
            CompoundTag t=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getCompound("muxi_console");
            if(!t.hasUUID("instance") || !t.getString("hash").matches("[a-f0-9]{64}"))return Optional.empty();
            return Optional.of(new Cartridge(t.getUUID("instance"),Platform.fromId(t.getString("platform")),t.getString("hash"),t.getLong("bytes"),t.getString("title")));
        }catch(RuntimeException invalid){return Optional.empty();}
    }
    public static ItemStack create(Cartridge card){ItemStack s=new ItemStack(ConsoleContent.CARTRIDGE.get());write(s,card);return s;}
    public static void write(ItemStack stack,Cartridge card){
        CompoundTag root=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag(),t=new CompoundTag();
        t.putUUID("instance",card.instanceId());t.putString("platform",card.platform().id());t.putString("hash",card.sha256());t.putLong("bytes",card.size());t.putString("title",card.title());root.put("muxi_console",t);
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(root));
        if(stack.is(ConsoleContent.CARTRIDGE.get())){stack.set(DataComponents.CUSTOM_MODEL_DATA,new CustomModelData(card.platform().customModelData()));stack.set(DataComponents.CUSTOM_NAME,Component.literal(card.title()+" · "+card.platform().id().toUpperCase(java.util.Locale.ROOT)));}
    }
    public static void clear(ItemStack stack){CompoundTag root=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();root.remove("muxi_console");if(root.isEmpty())stack.remove(DataComponents.CUSTOM_DATA);else stack.set(DataComponents.CUSTOM_DATA,CustomData.of(root));if(stack.is(ConsoleContent.CARTRIDGE.get())){stack.remove(DataComponents.CUSTOM_MODEL_DATA);stack.remove(DataComponents.CUSTOM_NAME);}}
    private CardData(){}
}
