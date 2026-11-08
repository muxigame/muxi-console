package net.muxigame.console.client.input;

import java.util.Set;
import net.muxigame.console.domain.Platform;
import net.muxigame.console.runtime.PortInput;

/** Held-world keyboard ownership: gamepad state is independent of the world's keyboard bindings. */
public final class HeldInputPolicy {
    /** The focused Screen owns all game keys; ordinary world movement and settings never send game input. */
    public static boolean captures(boolean gameScreen,boolean released,boolean leaving){return gameScreen&&!released&&!leaving;}
    public static boolean permits(int key,boolean modified,Set<Integer> worldKeys){return key>=32&&!modified&&!worldKeys.contains(key);}
    public static PortInput directions(Platform platform,PortInput input,boolean up,boolean down,boolean left,boolean right){
        int mask=input.buttons();short lx=input.leftX(),ly=input.leftY();
        if(platform==Platform.N64){
            if(left||right)lx=(short)(left==right?0:left?-32767:32767);
            if(up||down)ly=(short)(up==down?0:up?-32767:32767);
            if(up||down||left||right)mask&=~0xf0;
        }else{if(up)mask|=1<<4;if(down)mask|=1<<5;if(left)mask|=1<<6;if(right)mask|=1<<7;}
        if((mask&0x30)==0x30)mask&=~0x30;if((mask&0xc0)==0xc0)mask&=~0xc0;
        return new PortInput(mask,lx,ly,input.rightX(),input.rightY());
    }
    private HeldInputPolicy(){}
}
