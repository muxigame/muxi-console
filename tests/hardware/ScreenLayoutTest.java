package net.muxigame.console.tests.hardware;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.muxigame.console.mc.ScreenLayout;

/** Four-way real footprint and retained legacy tile positions; world migration is also exercised in native QA. */
public final class ScreenLayoutTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        check(ScreenLayout.WIDTH==2&&ScreenLayout.HEIGHT==2&&ScreenLayout.PARTS==4,"Monitor occupies two by two actual blocks");
        for(Direction facing:new Direction[]{Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST})for(BlockPos anchor:List.of(new BlockPos(0,64,0),new BlockPos(15,100,15),new BlockPos(-1,65,-1))){
            var current=ScreenLayout.parts(anchor,facing);var old=ScreenLayout.parts(anchor,facing,true);
            check(current.size()==4&&new HashSet<>(current).size()==4,"Exactly four distinct world tiles");
            check(old.size()==6&&new HashSet<>(old).size()==6,"Six legacy tiles remain addressable until migration");
            int[] retained={0,1,3,4};
            for(int i=0;i<4;i++){
                check(current.get(i).equals(old.get(retained[i])),"Legacy shrink retains anchor and first two columns without moving entities");
                check(ScreenLayout.index(ScreenLayout.newPart(i))==i,"New part ID preserves four-tile address");
                check(ScreenLayout.monitorPart(ScreenLayout.newPart(i))&&!ScreenLayout.legacyPart(ScreenLayout.newPart(i)),"New part IDs never alias old three-column saves");
            }
            var b=ScreenLayout.bounds(anchor,facing);var prior=ScreenLayout.bounds(anchor,facing,true);
            check(b.getYsize()==2&&b.getXsize()*b.getZsize()==2&&Math.min(b.getXsize(),b.getZsize())==1,"Two by two by one footprint for every facing");
            check(prior.getYsize()==2&&prior.getXsize()*prior.getZsize()==3,"Old render/removal bound still covers all six tiles");
            check(current.get(0).equals(anchor)&&current.get(2).equals(anchor.above()),"Anchor remains lower left; upper tile remains directly above");
            check(current.get(1).equals(anchor.relative(facing.getCounterClockWise())),"Second column grows toward viewer right");
            check(!current.contains(old.get(2))&&!current.contains(old.get(5)),"Only obsolete third-column tiles are reclaimed");
        }
        for(int part=1;part<=6;part++)check(ScreenLayout.legacyPart(part)&&!ScreenLayout.monitorPart(part)&&ScreenLayout.index(part)==part-1,"Old save part IDs retain old meaning");
        check(!ScreenLayout.legacyPart(0)&&!ScreenLayout.monitorPart(0),"Single-block legacy screen does not pretend to own extra tiles");
        for(int invalid:new int[]{-1,4,6}){try{ScreenLayout.part(BlockPos.ZERO,Direction.NORTH,invalid);throw new AssertionError("Invalid new index accepted");}catch(IllegalArgumentException expected){checks++;}}
        try{ScreenLayout.part(BlockPos.ZERO,Direction.UP,0);throw new AssertionError("Vertical facing accepted");}catch(IllegalArgumentException expected){checks++;}
        try{ScreenLayout.parts(BlockPos.ZERO,Direction.NORTH).add(BlockPos.ZERO);throw new AssertionError("Mutable footprint");}catch(UnsupportedOperationException expected){checks++;}
        for(var plane:List.of(ScreenLayout.monitorPlane(),ScreenLayout.singlePlane()))check(Math.abs((plane.x1()-plane.x0())/(plane.y1()-plane.y0())-16f/9f)<.00001,"Both standard and blocked legacy LCDs preserve computer-monitor aspect");
        System.out.println("ScreenLayoutTest: "+checks+" checks passed");
    }
}
