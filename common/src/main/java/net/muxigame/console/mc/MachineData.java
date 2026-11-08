package net.muxigame.console.mc;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Physical machine identity is independent of the installed, transferable cartridge. */
public final class MachineData {
    private static final String KEY="muxi_console_machine";
    public static Optional<UUID> read(ItemStack item){if(!machine(item))return Optional.empty();var data=item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();return data.hasUUID(KEY)?Optional.of(data.getUUID(KEY)):Optional.empty();}
    /** Server-side allocation only; renderers and read-only adapters must use read(). */
    public static UUID ensure(ItemStack item){if(!machine(item)||item.getCount()!=1)throw new IllegalArgumentException("A single physical machine is required");return read(item).orElseGet(()->{UUID id=UUID.randomUUID();write(item,id);return id;});}
    public static void write(ItemStack item,UUID identity){if(!machine(item)||item.getCount()!=1||identity==null)throw new IllegalArgumentException("A single physical machine is required");var data=item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();data.putUUID(KEY,identity);item.set(DataComponents.CUSTOM_DATA,CustomData.of(data));}
    private static boolean machine(ItemStack item){return item.is(ConsoleContent.HANDHELD.get())||item.is(ConsoleContent.DESK_ITEM.get());}
    private MachineData(){}
}
