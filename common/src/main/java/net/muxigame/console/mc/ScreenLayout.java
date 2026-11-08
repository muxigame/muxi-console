package net.muxigame.console.mc;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/** A screen item occupies six actual blocks; the anchor is the front viewer's lower-left tile. */
public final class ScreenLayout {
    public static final int WIDTH=3,HEIGHT=2,PARTS=WIDTH*HEIGHT;
    public static BlockPos part(BlockPos anchor,Direction facing,int index){
        if(facing.getAxis().isVertical()||index<0||index>=PARTS)throw new IllegalArgumentException("Invalid screen tile");
        return anchor.relative(facing.getCounterClockWise(),index%WIDTH).above(index/WIDTH);
    }
    public static List<BlockPos> parts(BlockPos anchor,Direction facing){
        List<BlockPos> positions=new ArrayList<>(PARTS);for(int index=0;index<PARTS;index++)positions.add(part(anchor,facing,index));return List.copyOf(positions);
    }
    public static AABB bounds(BlockPos anchor,Direction facing){
        BlockPos end=part(anchor,facing,PARTS-1);return new AABB(Math.min(anchor.getX(),end.getX()),anchor.getY(),Math.min(anchor.getZ(),end.getZ()),Math.max(anchor.getX(),end.getX())+1,anchor.getY()+HEIGHT,Math.max(anchor.getZ(),end.getZ())+1);
    }
    private ScreenLayout(){}
}
