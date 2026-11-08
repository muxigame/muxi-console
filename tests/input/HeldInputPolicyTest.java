package net.muxigame.console.tests.input;

import java.util.Set;
import net.muxigame.console.client.input.HeldInputPolicy;
import net.muxigame.console.domain.Platform;
import net.muxigame.console.runtime.PortInput;

/** Keyboard/gamepad ownership examples, no physical keyboard polling or Minecraft singleton. */
public final class HeldInputPolicyTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        for(boolean released:new boolean[]{false,true})for(boolean leaving:new boolean[]{false,true}){
            check(!HeldInputPolicy.captures(false,released,leaving),"World and settings never own game controls");
            check(HeldInputPolicy.captures(true,released,leaving)==(!released&&!leaving),"Only a live focused game screen captures controls");
        }
        var world=Set.of(87,65,83,68,69,81,70,49,84,32);
        for(int key:world)check(!HeldInputPolicy.permits(key,false,world),"World binding cannot simultaneously press a game button");
        for(int key:new int[]{262,263,264,265,257,74,75})check(HeldInputPolicy.permits(key,false,world),"Unassigned navigation/game key remains usable");
        check(!HeldInputPolicy.permits(74,true,world),"Modified world shortcut does not leak to game");check(!HeldInputPolicy.permits(-1,false,world),"Unbound key is not a physical input");
        PortInput gamepad=new PortInput(1<<8,(short)1234,(short)-2345,(short)2222,(short)-3333);
        for(var platform:Platform.values()){
            check(HeldInputPolicy.directions(platform,gamepad,false,false,false,false).equals(gamepad),"No keyboard direction preserves entire gamepad input");
            PortInput up=HeldInputPolicy.directions(platform,gamepad,true,false,false,false);
            check((up.buttons()&(1<<8))!=0&&up.rightX()==2222&&up.rightY()==-3333,"Direction preserves gamepad action and right stick");
            if(platform==Platform.N64){check(up.leftY()==-32767&&up.leftX()==1234,"World arrow moves N64 analog without replacing other axis");check((up.buttons()&0xf0)==0,"N64 arrow alternative is not duplicate Dpad");}
            else check((up.buttons()&0xf0)==0x10&&up.leftY()==-2345,"Other platforms receive Up without mutating analog stick");
            PortInput opposite=HeldInputPolicy.directions(platform,gamepad,true,true,true,true);
            if(platform==Platform.N64)check(opposite.leftX()==0&&opposite.leftY()==0,"Opposite world arrows center N64 stick");
            else check((opposite.buttons()&0xf0)==0,"Opposite world arrows cancel digital directions");
        }
        PortInput mixed=HeldInputPolicy.directions(Platform.GBA,new PortInput((1<<5)|(1<<8),(short)0,(short)0,(short)0,(short)0),true,false,false,false);
        check((mixed.buttons()&0x30)==0&&(mixed.buttons()&(1<<8))!=0,"Opposing keyboard/gamepad digital state centers only directions");
        System.out.println("HeldInputPolicyTest: "+checks+" checks passed");
    }
}
