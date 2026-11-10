package net.muxigame.console.client.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.muxigame.console.mc.DockMenu;

/** Desktop console: the same vanilla slot screen as the dock, holding a cartridge or disc. */
public final class DeviceScreen extends DockScreen {
    public DeviceScreen(DockMenu menu,Inventory inventory,Component title){super(menu,inventory,title);}
}
