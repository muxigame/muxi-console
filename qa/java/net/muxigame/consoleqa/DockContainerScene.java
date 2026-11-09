package net.muxigame.consoleqa;

import com.google.gson.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.muxigame.console.mc.*;

/** Supplies empty fixture hardware only; all installed machines/cards arrive by normal player clicks. */
final class DockContainerScene {
    private final QaOwner owner;
    private boolean prepared;
    private static final BlockPos POSITION=new BlockPos(12,90,6);
    DockContainerScene(QaOwner owner){this.owner=owner;}
    private ServerPlayer player(MinecraftServer server,String role)throws Exception {
        ServerPlayer p=server.getPlayerList().getPlayerByName(owner.playerName(role));
        if(p==null)throw new IllegalStateException("Two actual connected fixture players required");return p;
    }
    JsonObject command(MinecraftServer server,JsonObject command){try{
        String action=command.get("action").getAsString();
        if(action.equals("dockslot_prepare")){
            ServerPlayer p=player(server,"host");
            if(prepared||server.getPlayerList().getPlayerCount()!=2||!ConsoleServer.state(server).rooms.isEmpty()||!server.overworld().getBlockState(POSITION).isAir()||!p.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Fresh idle empty dock fixture required");
            for(int i:new int[]{3,5,6})if(!p.getInventory().getItem(i).isEmpty())throw new IllegalStateException("Ordinary fixture slots must be empty");
            p.getInventory().setItem(3,new ItemStack(Items.STONE,3));
            p.getInventory().setItem(5,new ItemStack(ConsoleContent.DOCK_ITEM.get()));
            p.getInventory().setItem(6,new ItemStack(Items.DIAMOND_PICKAXE));
            p.inventoryMenu.broadcastChanges();prepared=true;
        }else if(!action.equals("dockslot_observe"))throw new IllegalArgumentException("Unknown dock slot fixture action");
        JsonObject out=new JsonObject();out.addProperty("prepared",prepared);out.addProperty("position",POSITION.asLong());
        JsonArray players=new JsonArray();
        for(String role:List.of("host","guest")){
            ServerPlayer p=player(server,role);JsonObject row=new JsonObject();row.addProperty("role",role);row.addProperty("menu",p.containerMenu.getClass().getName());row.addProperty("stillValid",p.containerMenu.stillValid(p));
            row.addProperty("cursorNbt",p.containerMenu.getCarried().saveOptional(p.registryAccess()).toString());
            if(p.containerMenu instanceof DockMenu dock)row.add("context",dock.context());players.add(row);
        }out.add("players",players);
        if(server.overworld().getBlockEntity(POSITION) instanceof DeviceEntity d){
            out.add("device",ConsoleServer.view(d));out.addProperty("machineNbt",d.dockedHandheld.saveOptional(server.registryAccess()).toString());
            var tag=d.saveWithFullMetadata(server.registryAccess());var copy=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(POSITION,d.getBlockState(),tag,server.registryAccess());
            out.addProperty("nbtRoundTrip",copy instanceof DeviceEntity restored&&ItemStack.matches(restored.dockedHandheld,d.dockedHandheld)&&restored.identity.equals(d.identity));
        }
        out.add("saved",MachineScene.saved(server,true));return out;
    }catch(Exception error){throw new IllegalStateException(error);}}
}
