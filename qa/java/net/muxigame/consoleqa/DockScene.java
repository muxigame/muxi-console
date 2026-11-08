package net.muxigame.consoleqa;

import com.google.gson.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.muxigame.console.mc.*;

/** Ordinary isolated fixtures plus read-only product state. Never mints cartridge metadata. */
final class DockScene {
    static final BlockPos DOCK=new BlockPos(12,90,6), SCREEN=new BlockPos(16,90,6), TEST=new BlockPos(16,90,18);
    private final QaOwner owner;
    private boolean prepared;
    DockScene(QaOwner owner){this.owner=owner;}
    private ServerPlayer player(MinecraftServer server,String role){try{ServerPlayer p=server.getPlayerList().getPlayerByName(owner.playerName(role));if(p==null)throw new IllegalStateException("Real fixture player is missing");return p;}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    JsonObject command(MinecraftServer server,JsonObject command){
        String action=command.get("action").getAsString();
        if(action.equals("dock_prepare"))return prepare(server);
        if(!prepared)throw new IllegalStateException("Prepare the fresh dock fixture first");
        switch(action){
            case "dock_camera"->{String role=command.has("role")?command.get("role").getAsString():"host";if(!Set.of("host","guest").contains(role))throw new IllegalArgumentException("Real fixture roles only");double x=command.get("x").getAsDouble(),y=command.has("y")?command.get("y").getAsDouble():90,z=command.get("z").getAsDouble();if(x<3||x>25||y<90||y>94||z<4||z>25)throw new IllegalArgumentException("Camera outside isolated platform");player(server,role).connection.teleport(x,y,z,command.get("yaw").getAsFloat(),command.get("pitch").getAsFloat());}
            case "dock_mode"->{String mode=command.get("mode").getAsString();if(!Set.of("survival","creative").contains(mode))throw new IllegalArgumentException("Fixture modes only");player(server,"host").setGameMode(mode.equals("survival")?GameType.SURVIVAL:GameType.CREATIVE);}
            case "dock_obstacle"->{BlockPos pos=TEST.offset(1,1,0);if(command.get("present").getAsBoolean()){if(!server.overworld().getBlockState(pos).isAir())throw new IllegalStateException("Obstacle target is occupied");server.overworld().setBlock(pos,Blocks.STONE.defaultBlockState(),3);}else{if(!server.overworld().getBlockState(pos).is(Blocks.STONE))throw new IllegalStateException("Only the fixture stone may be removed");server.overworld().removeBlock(pos,false);}}
            case "dock_observe"->{ }
            default->throw new IllegalArgumentException("Unsupported dock server action");
        }
        return observe(server);
    }
    private JsonObject prepare(MinecraftServer server){
        if(prepared||server.overworld().getGameTime()>12000||server.getPlayerList().getPlayerCount()!=2)throw new IllegalStateException("Fresh two-player dock fixture required");
        try{if(!ConsoleServer.state(server).rooms.isEmpty())throw new IllegalStateException("No active product session during setup");}catch(java.io.IOException e){throw new IllegalStateException(e);}
        var level=server.overworld();
        for(int x=3;x<=25;x++)for(int z=4;z<=25;z++){BlockPos floor=new BlockPos(x,89,z);if(level.getBlockState(floor).isAir())level.setBlock(floor,Blocks.SMOOTH_STONE.defaultBlockState(),3);}
        for(BlockPos pos:List.of(DOCK,SCREEN,TEST))if(!level.getBlockState(pos).isAir())throw new IllegalStateException("New fixture device position is occupied");
        ServerPlayer host=player(server,"host");
        if(CardData.read(host.getInventory().getItem(2)).isPresent())throw new IllegalStateException("Fixture can configure only an empty ordinary handheld");
        ItemStack handheld=HardwareCatalog.variant(new ItemStack(ConsoleContent.HANDHELD.get()),5);CompoundTag tag=new CompoundTag();tag.putString("qa_preserve","original-gba-shell");handheld.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
        host.getInventory().setItem(2,handheld);
        host.getInventory().setItem(4,new ItemStack(ConsoleContent.SCREEN_ITEM.get()));
        host.getInventory().setItem(5,new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("muxi_console","handheld_dock"))));
        host.getInventory().setItem(6,new ItemStack(Items.DIAMOND_PICKAXE));
        host.inventoryMenu.broadcastChanges();
        player(server,"guest").connection.teleport(5.5,90,14.5,180,10);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);level.setDayTime(6000);
        prepared=true;return observe(server);
    }
    private static JsonObject item(ItemStack stack){JsonObject row=new JsonObject();row.addProperty("item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());row.addProperty("count",stack.getCount());row.addProperty("model",HardwareCatalog.model(stack));row.addProperty("qaPreserve",stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getString("qa_preserve"));CardData.read(stack).ifPresent(c->{row.addProperty("instance",c.instanceId().toString());row.addProperty("hash",c.sha256());row.addProperty("platform",c.platform().id());});return row;}
    JsonObject observe(MinecraftServer server){
        JsonObject out=new JsonObject();out.addProperty("prepared",prepared);if(!prepared)return out;
        var level=server.overworld();JsonArray cells=new JsonArray(),drops=new JsonArray(),instances=new JsonArray(),saved=new JsonArray();Set<UUID> ids=new HashSet<>();
        for(BlockPos pos:BlockPos.betweenClosed(3,90,4,25,94,25)){
            var state=level.getBlockState(pos);if(!state.is(ConsoleContent.SCREEN.get())||!state.hasProperty(ScreenBlock.PART))continue;
            int part=state.getValue(ScreenBlock.PART);if(part==0)continue;JsonObject row=new JsonObject();row.addProperty("x",pos.getX());row.addProperty("y",pos.getY());row.addProperty("z",pos.getZ());row.addProperty("part",part);row.addProperty("facing",state.getValue(DeviceBlock.FACING).getName());
            var bounds=state.getShape(level,pos).bounds();row.addProperty("sizeX",bounds.getXsize());row.addProperty("sizeY",bounds.getYsize());row.addProperty("sizeZ",bounds.getZsize());
            if(level.getBlockEntity(pos)instanceof DeviceEntity d){row.addProperty("origin",d.screenOrigin==null?"":d.screenOrigin.toShortString());row.addProperty("identity",d.identity.toString());row.addProperty("source",d.linkedConsole==null?"":d.linkedConsole.toShortString());}cells.add(row);
        }
        if(level.getBlockEntity(DOCK)instanceof DeviceEntity dock){out.add("device",ConsoleServer.view(dock));out.add("dockedHandheld",item(dock.dockedHandheld));CardData.read(dock.dockedHandheld).ifPresent(c->{JsonObject row=item(dock.dockedHandheld);row.addProperty("location","dock");instances.add(row);ids.add(c.instanceId());});
            var tag=dock.saveWithFullMetadata(level.registryAccess());var copy=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(DOCK,dock.getBlockState(),tag,level.registryAccess());out.addProperty("nbtRoundTrip",copy instanceof DeviceEntity d&&ItemStack.matches(d.dockedHandheld,dock.dockedHandheld)&&d.identity.equals(dock.identity));}
        else out.addProperty("dockBlock",BuiltInRegistries.BLOCK.getKey(level.getBlockState(DOCK).getBlock()).toString());
        int screenInventory=0,screenDrops=0;
        for(ServerPlayer p:server.getPlayerList().getPlayers())for(int i=0;i<p.getInventory().getContainerSize();i++){ItemStack stack=p.getInventory().getItem(i);if(stack.is(ConsoleContent.SCREEN_ITEM.get()))screenInventory+=stack.getCount();int slot=i;CardData.read(stack).ifPresent(c->{JsonObject row=item(stack);row.addProperty("location",p.getGameProfile().getName()+"/"+slot);instances.add(row);ids.add(c.instanceId());});}
        for(ItemEntity entity:level.getEntitiesOfClass(ItemEntity.class,new AABB(3,89,4,26,96,26))){if(entity.getItem().is(ConsoleContent.SCREEN_ITEM.get()))screenDrops+=entity.getItem().getCount();JsonObject row=item(entity.getItem());row.addProperty("x",entity.getX());row.addProperty("y",entity.getY());row.addProperty("z",entity.getZ());drops.add(row);CardData.read(entity.getItem()).ifPresent(c->{row.addProperty("location","world");instances.add(row);ids.add(c.instanceId());});}
        try{for(UUID id:ids)ConsoleServer.state(server).states.latest(id).ifPresent(s->{JsonObject row=new JsonObject();row.addProperty("card",s.cardUUID().toString());row.addProperty("hash",s.hash());row.addProperty("generation",s.generationID().toString());row.addProperty("frame",s.frame());row.addProperty("size",s.size());saved.add(row);});}catch(java.io.IOException e){throw new IllegalStateException(e);}
        out.add("cells",cells);out.add("drops",drops);out.add("instances",instances);out.add("states",saved);out.addProperty("screenInventory",screenInventory);out.addProperty("screenDrops",screenDrops);out.addProperty("serverTick",server.getTickCount());return out;
    }
}
