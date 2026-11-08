package net.muxigame.consoleqa;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.muxigame.console.mc.ConsoleContent;
import net.muxigame.console.mc.ConsoleServer;
import net.muxigame.console.mc.DeviceBlock;
import net.muxigame.console.mc.DeviceEntity;
import net.muxigame.console.mc.ScreenBlock;

/** Valid historical empty screen data only; no cartridge, ROM, checkpoint or migration result is forged. */
final class PolishScene {
    private UUID legacy;
    private Direction legacyFacing;
    private UUID single;
    private final BlockPos singleOrigin=new BlockPos(7,90,18),singleObstacle=new BlockPos(8,91,18);
    private final BlockPos origin=new BlockPos(16,90,18),source=new BlockPos(10,90,8),sentinel=new BlockPos(20,90,18);
    JsonObject command(MinecraftServer server,JsonObject j){
        if(j.get("action").getAsString().equals("polish_single_prepare"))return single(server);
        if(j.get("action").getAsString().equals("polish_single_clear_obstacle")){
            if(single==null||!server.overworld().getBlockState(singleObstacle).is(Blocks.STONE))throw new IllegalStateException("Only the ordinary single-screen fixture obstacle may be cleared");server.overworld().removeBlock(singleObstacle,false);return observe(server);
        }
        if(!j.get("action").getAsString().equals("polish_legacy_prepare"))throw new IllegalArgumentException("Unknown polish fixture action");
        if(legacy!=null)throw new IllegalStateException("Only one old-screen fixture in a fresh run");
        try{if(!ConsoleServer.state(server).rooms.isEmpty())throw new IllegalStateException("Legacy migration setup requires no live session");}catch(java.io.IOException e){throw new IllegalStateException(e);}
        Direction facing=Direction.valueOf(j.get("facing").getAsString());if(facing.getAxis().isVertical())throw new IllegalArgumentException("Horizontal legacy screen only");
        var level=server.overworld();if(!(level.getBlockEntity(source)instanceof DeviceEntity))throw new IllegalStateException("Ordinary console fixture is missing");
        for(int i=0;i<6;i++){BlockPos p=origin.relative(facing.getCounterClockWise(),i%3).above(i/3);if(!level.getBlockState(p).isAir())throw new IllegalStateException("Historical screen footprint is occupied");}
        if(!level.getBlockState(sentinel).isAir())throw new IllegalStateException("Migration sentinel target occupied");level.setBlock(sentinel,Blocks.COPPER_BLOCK.defaultBlockState(),3);
        legacy=UUID.randomUUID();legacyFacing=facing;JsonArray old=new JsonArray();
        for(int i=0;i<6;i++){
            BlockPos p=origin.relative(facing.getCounterClockWise(),i%3).above(i/3);var state=ConsoleContent.SCREEN.get().defaultBlockState().setValue(DeviceBlock.FACING,facing).setValue(ScreenBlock.PART,i+1);level.setBlock(p,state,3);
            if(!(level.getBlockEntity(p)instanceof DeviceEntity entity))throw new IllegalStateException("Actual old screen entity missing");entity.identity=legacy;entity.screenOrigin=origin;if(i==0)entity.linkedConsole=source;
            CompoundTag saved=entity.saveWithFullMetadata(level.registryAccess());BlockEntity copy=BlockEntity.loadStatic(p,state,saved,level.registryAccess());
            if(!(copy instanceof DeviceEntity restored)||!restored.identity.equals(legacy)||!origin.equals(restored.screenOrigin)||i==0&&!source.equals(restored.linkedConsole))throw new IllegalStateException("Valid historical screen metadata failed normal load round trip");
            level.setBlockEntity(restored);restored.changed();JsonObject row=new JsonObject();row.addProperty("part",i+1);row.addProperty("position",p.toShortString());row.addProperty("serializedIdentity",legacy.toString());row.addProperty("normalNbtLoad",true);old.add(row);
        }
        JsonObject out=observe(server);out.add("beforeScheduledMigration",old);out.addProperty("mintedCartridges",0);out.addProperty("migrationCalledByQa",false);return out;
    }
    private JsonObject single(MinecraftServer server){
        if(single!=null)throw new IllegalStateException("Single-screen fixture already exists");var level=server.overworld();for(int i=0;i<4;i++)if(!level.getBlockState(singleOrigin.offset(i%2,i/2,0)).isAir())throw new IllegalStateException("Single-screen target occupied");
        level.setBlock(singleObstacle,Blocks.STONE.defaultBlockState(),3);var state=ConsoleContent.SCREEN.get().defaultBlockState().setValue(DeviceBlock.FACING,Direction.SOUTH).setValue(ScreenBlock.PART,0);level.setBlock(singleOrigin,state,3);
        if(!(level.getBlockEntity(singleOrigin)instanceof DeviceEntity entity))throw new IllegalStateException("Actual old single-screen entity missing");single=entity.identity;entity.linkedConsole=source;entity.screenOrigin=null;
        var copy=BlockEntity.loadStatic(singleOrigin,state,entity.saveWithFullMetadata(level.registryAccess()),level.registryAccess());if(!(copy instanceof DeviceEntity restored)||!single.equals(restored.identity)||restored.screenOrigin!=null)throw new IllegalStateException("Old single-screen NBT must load normally");level.setBlockEntity(restored);restored.changed();JsonObject out=observe(server);out.addProperty("normalNbtLoad",true);out.addProperty("migrationCalledByQa",false);return out;
    }
    JsonObject observe(MinecraftServer server){
        JsonObject out=new JsonObject();out.addProperty("legacyIdentity",legacy==null?"":legacy.toString());out.addProperty("singleIdentity",single==null?"":single.toString());
        if(single!=null){JsonArray singleCells=new JsonArray();for(int i=0;i<4;i++){BlockPos p=singleOrigin.offset(i%2,i/2,0);var state=server.overworld().getBlockState(p);JsonObject row=new JsonObject();row.addProperty("index",i);row.addProperty("position",p.toShortString());row.addProperty("air",state.isAir());if(state.is(ConsoleContent.SCREEN.get())){row.addProperty("part",state.getValue(ScreenBlock.PART));if(server.overworld().getBlockEntity(p)instanceof DeviceEntity e){row.addProperty("identity",e.identity.toString());row.addProperty("origin",e.screenOrigin==null?"":e.screenOrigin.toShortString());row.addProperty("source",e.linkedConsole==null?"":e.linkedConsole.toShortString());}}singleCells.add(row);}out.add("singleCells",singleCells);out.addProperty("singleObstaclePresent",server.overworld().getBlockState(singleObstacle).is(Blocks.STONE));}
        if(legacy==null)return out;out.addProperty("legacyOrigin",origin.toShortString());out.addProperty("legacyFacing",legacyFacing.getName());out.addProperty("legacySource",source.toShortString());out.addProperty("sentinelPreserved",server.overworld().getBlockState(sentinel).is(Blocks.COPPER_BLOCK));JsonArray cells=new JsonArray();
        for(int i=0;i<6;i++){BlockPos p=origin.relative(legacyFacing.getCounterClockWise(),i%3).above(i/3);var state=server.overworld().getBlockState(p);JsonObject row=new JsonObject();row.addProperty("oldIndex",i);row.addProperty("position",p.toShortString());row.addProperty("air",state.isAir());if(state.is(ConsoleContent.SCREEN.get())){row.addProperty("part",state.getValue(ScreenBlock.PART));if(server.overworld().getBlockEntity(p)instanceof DeviceEntity d){row.addProperty("identity",d.identity.toString());row.addProperty("origin",d.screenOrigin==null?"":d.screenOrigin.toShortString());row.addProperty("source",d.linkedConsole==null?"":d.linkedConsole.toShortString());}}cells.add(row);}out.add("legacyCells",cells);return out;
    }
}
