package net.muxigame.console.mc;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/** Four real blocks enclose a thin computer monitor, support and foot; old IDs remain readable. */
public final class ScreenLayout {
    public static final int WIDTH=2,HEIGHT=2,PARTS=WIDTH*HEIGHT,FIRST_PART=7,LAST_PART=10;
    public static final int LEGACY_WIDTH=3,LEGACY_PARTS=6;
    private static final HardwareCatalog.Plane MONITOR=new HardwareCatalog.Plane(-14.6f,13.15f,14.6f,29.575f,6.14f);
    private static final HardwareCatalog.Plane SINGLE=new HardwareCatalog.Plane(1.1f,6.65f,14.9f,14.4125f,6.14f);
    private static final HardwareCatalog.Plane LEGACY=new HardwareCatalog.Plane(-31.25f,.5f,15.25f,31.5f,6.90f);
    public static boolean legacyPart(int part){return part>=1&&part<=LEGACY_PARTS;}
    public static boolean monitorPart(int part){return part>=FIRST_PART&&part<=LAST_PART;}
    public static int newPart(int index){if(index<0||index>=PARTS)throw new IllegalArgumentException("Invalid monitor tile");return FIRST_PART+index;}
    public static int index(int part){if(monitorPart(part))return part-FIRST_PART;if(legacyPart(part))return part-1;throw new IllegalArgumentException("Not a multi-block screen tile");}
    public static BlockPos part(BlockPos anchor,Direction facing,int index){return part(anchor,facing,index,false);}
    public static BlockPos part(BlockPos anchor,Direction facing,int index,boolean legacy){
        int width=legacy?LEGACY_WIDTH:WIDTH,count=legacy?LEGACY_PARTS:PARTS;
        if(facing.getAxis().isVertical()||index<0||index>=count)throw new IllegalArgumentException("Invalid screen tile");
        return anchor.relative(facing.getCounterClockWise(),index%width).above(index/width);
    }
    public static List<BlockPos> parts(BlockPos anchor,Direction facing){return parts(anchor,facing,false);}
    public static List<BlockPos> parts(BlockPos anchor,Direction facing,boolean legacy){
        int count=legacy?LEGACY_PARTS:PARTS;List<BlockPos> positions=new ArrayList<>(count);for(int index=0;index<count;index++)positions.add(part(anchor,facing,index,legacy));return List.copyOf(positions);
    }
    public static AABB bounds(BlockPos anchor,Direction facing){return bounds(anchor,facing,false);}
    public static AABB bounds(BlockPos anchor,Direction facing,boolean legacy){
        BlockPos end=part(anchor,facing,(legacy?LEGACY_PARTS:PARTS)-1,legacy);return new AABB(Math.min(anchor.getX(),end.getX()),anchor.getY(),Math.min(anchor.getZ(),end.getZ()),Math.max(anchor.getX(),end.getX())+1,anchor.getY()+HEIGHT,Math.max(anchor.getZ(),end.getZ())+1);
    }
    public static AABB bounds(DeviceEntity device){return device.largeScreen()?bounds(device.screenAnchor(),device.getBlockState().getValue(DeviceBlock.FACING),legacyPart(device.getBlockState().getValue(ScreenBlock.PART))):new AABB(device.getBlockPos());}
    public static HardwareCatalog.Plane plane(DeviceEntity device){int part=device.getBlockState().getValue(ScreenBlock.PART);return legacyPart(part)?LEGACY:monitorPart(part)?MONITOR:SINGLE;}
    public static HardwareCatalog.Plane monitorPlane(){return MONITOR;}
    public static HardwareCatalog.Plane singlePlane(){return SINGLE;}
    private ScreenLayout(){}
}
