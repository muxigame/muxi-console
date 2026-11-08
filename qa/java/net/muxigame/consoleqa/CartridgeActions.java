package net.muxigame.consoleqa;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.domain.Hashing;
import net.muxigame.console.mc.CardData;
import net.muxigame.console.mc.ConsoleContent;
import net.muxigame.console.mc.HardwareCatalog;

/** Ordinary vanilla inventory clicks; no direct mutation of cartridge contents, UUIDs or progress. */
final class CartridgeActions {
    private record Pending(int destination,long expires,String card){}
    private static Pending pending;private static JsonObject busyAttempt=new JsonObject();private static long messageSequence;
    static void message(String text){messageSequence++;if(busyAttempt.has("executed")&&busyAttempt.get("executed").getAsBoolean()&&text.equals("卡带正在游玩或保存，请先保存并离开后再插拔")){busyAttempt.addProperty("serverBusyDenied",true);busyAttempt.addProperty("serverMessage",text);busyAttempt.addProperty("messageSequence",messageSequence);}}
    static void tick(Minecraft mc){
        if(pending==null||mc.player==null||mc.level==null)return;var state=ConsoleClient.INSTANCE.snapshot();
        if(mc.level.getGameTime()>pending.expires()){busyAttempt.addProperty("expired",true);pending=null;return;}
        if(!state.status().equals("正在保存并离开…")&&!state.status().equals("正在保存并同步会话…"))return;
        if(!(mc.screen instanceof InventoryScreen)||!CardData.read(mc.player.inventoryMenu.getCarried()).map(c->c.instanceId().toString().equals(pending.card())).orElse(false)){busyAttempt.addProperty("invalidated",true);pending=null;return;}
        busyAttempt.addProperty("observedStatus",state.status());busyAttempt.addProperty("room",state.room());busyAttempt.addProperty("epoch",state.epoch());busyAttempt.addProperty("workerRunning",state.workerRunning());busyAttempt.addProperty("executed",true);
        mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId,menuSlot(pending.destination()),1,ClickType.PICKUP,mc.player);pending=null;
    }
    static void command(Minecraft mc,JsonObject j)throws Exception{
        if(mc.player==null||mc.gameMode==null)throw new IllegalStateException("A real connected player is required");
        switch(j.get("action").getAsString()){
            case "cartridge_inventory"->{if(mc.screen!=null)throw new IllegalStateException("Return to the world before opening inventory");mc.setScreen(new InventoryScreen(mc.player));}
            case "cartridge_click"->{
                inventory(mc);int button=j.get("button").getAsInt();if(button<0||button>1)throw new IllegalArgumentException("Ordinary left/right inventory click only");
                mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId,menuSlot(j.get("slot").getAsInt()),button,ClickType.PICKUP,mc.player);
            }
            case "cartridge_swap"->{
                inventory(mc);int hotbar=j.get("hotbar").getAsInt();if(hotbar<0||hotbar>8)throw new IllegalArgumentException("Ordinary hotbar swap only");
                mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId,menuSlot(j.get("slot").getAsInt()),hotbar,ClickType.SWAP,mc.player);
            }
            case "cartridge_close_inventory"->{inventory(mc);mc.screen.onClose();}
            case "cartridge_shift"->{boolean pressed=j.get("pressed").getAsBoolean();mc.options.keyShift.setDown(pressed);mc.player.setShiftKeyDown(pressed);mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player,pressed?ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY:ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));}
            case "cartridge_use"->{if(mc.screen!=null)throw new IllegalStateException("Normal held use starts in the world");InteractionHand hand=InteractionHand.valueOf(j.get("hand").getAsString());if(!mc.player.getItemInHand(hand).is(ConsoleContent.HANDHELD.get()))throw new IllegalStateException("Explicit actual handheld required");mc.gameMode.useItem(mc.player,hand);}
            case "cartridge_busy_pair"->{inventory(mc);int from=j.get("slot").getAsInt(),destination=j.get("destination").getAsInt();menuSlot(destination);if(pending!=null||!mc.player.getInventory().getItem(from).is(ConsoleContent.HANDHELD.get())||CardData.read(mc.player.getInventory().getItem(from)).isEmpty()||!mc.player.getInventory().getItem(destination).isEmpty()||!ConsoleClient.INSTANCE.snapshot().ready())throw new IllegalStateException("A real running handheld and an empty destination are required");String card=CardData.read(mc.player.getInventory().getItem(from)).orElseThrow().instanceId().toString();busyAttempt=new JsonObject();busyAttempt.addProperty("card",card);busyAttempt.addProperty("executed",false);pending=new Pending(destination,mc.level.getGameTime()+60,card);mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId,menuSlot(from),0,ClickType.PICKUP,mc.player);}
            case "cartridge_cancel_pending"->pending=null;
            case "cartridge_cache"->{ }
            default->throw new IllegalArgumentException("Unknown cartridge frontend action");
        }
    }
    private static void inventory(Minecraft mc){if(!(mc.screen instanceof InventoryScreen)||mc.player.containerMenu!=mc.player.inventoryMenu)throw new IllegalStateException("Actual vanilla inventory screen required");}
    private static int menuSlot(int slot){if(slot>=0&&slot<=8)return slot+36;if(slot>=9&&slot<=35)return slot;if(slot==40)return 45;throw new IllegalArgumentException("Inventory/hotbar/offhand only");}
    static JsonObject stack(Minecraft mc,ItemStack item){
        JsonObject out=new JsonObject();out.addProperty("empty",item.isEmpty());out.addProperty("count",item.getCount());if(item.isEmpty())return out;
        out.addProperty("item",BuiltInRegistries.ITEM.getKey(item.getItem()).toString());out.addProperty("model",HardwareCatalog.model(item));out.addProperty("name",item.getHoverName().getString());
        net.muxigame.console.mc.MachineData.read(item).ifPresent(id->out.addProperty("machine",id.toString()));
        out.addProperty("data",item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().toString());out.addProperty("nbt",item.saveOptional(mc.level.registryAccess()).toString());
        CardData.read(item).ifPresent(c->{out.addProperty("instance",c.instanceId().toString());out.addProperty("hash",c.sha256());out.addProperty("platform",c.platform().id());out.addProperty("title",c.title());out.addProperty("bytes",c.size());});
        if(item.is(ConsoleContent.HANDHELD.get())){
            out.add("inserted",stack(mc,CardData.inserted(item)));var contents=item.get(DataComponents.CONTAINER);out.addProperty("storedSlots",contents==null?0:contents.stream().filter(s->!s.isEmpty()).count());
        }
        return out;
    }
    static JsonObject observe(Minecraft mc){
        JsonObject out=new JsonObject();out.add("progress",new Gson().toJsonTree(ConsoleClient.INSTANCE.progressSnapshot()));out.addProperty("screenSource",ConsoleClient.INSTANCE.screenConsole());out.add("busyAttempt",busyAttempt.deepCopy());
        out.add("lcdLoading",new Gson().toJsonTree(net.muxigame.console.client.LcdLoadingRenderer.snapshot()));out.add("lcdLoadings",new Gson().toJsonTree(net.muxigame.console.client.LcdLoadingRenderer.snapshots()));out.add("insertedMediaAudit",new Gson().toJsonTree(net.muxigame.console.client.InsertedMediaRenderer.audit()));
        if(mc.player==null||mc.level==null)return out;
        out.addProperty("selected",mc.player.getInventory().selected);out.add("cursor",stack(mc,mc.player.inventoryMenu.getCarried()));JsonArray items=new JsonArray();
        for(int slot=0;slot<mc.player.getInventory().getContainerSize();slot++){ItemStack item=mc.player.getInventory().getItem(slot);if(item.isEmpty())continue;JsonObject row=stack(mc,item);row.addProperty("slot",slot);items.add(row);}out.add("items",items);
        return out;
    }
    static JsonObject cache(Minecraft mc,String hash)throws Exception{
        if(!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Expected actual diagnostic SHA256");JsonObject out=new JsonObject();Path root=mc.gameDirectory.toPath().resolve("muxi-console/roms").toAbsolutePath().normalize();JsonArray files=new JsonArray();
        if(Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(root))try(var entries=Files.list(root)){for(Path file:entries.filter(p->p.getFileName().toString().startsWith(hash+".")).toList()){
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw new IllegalStateException("Unexpected cache entry");JsonObject row=new JsonObject();row.addProperty("name",file.getFileName().toString());row.addProperty("bytes",Files.size(file));row.addProperty("sha256",Hashing.sha256(file));files.add(row);
        }}out.add("files",files);return out;
    }
}
