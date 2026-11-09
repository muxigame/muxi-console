package net.muxigame.console.tests.stream;

import net.muxigame.console.domain.*;

/** Slow execution, cumulative ACKs, duplicate/foreign boundaries and epoch rollover. */
public final class FrameFlowWindowTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("Frame flow "+checks);}
    private static void denied(Runnable action){try{action.run();throw new AssertionError("Invalid frame flow accepted");}catch(DomainException expected){checks++;}}
    public static void main(String[] args){
        var window=new FrameFlowWindow(8,180000);window.reset(42,0,1000);
        for(int tick=0;tick<8;tick++)window.issued(42,tick,(tick+1)*3,1000+tick*50);
        check(window.outstanding()==8&&!window.available(42));denied(()->window.issued(42,8,27,1400));
        denied(()->window.acknowledge(41,7,24,1400));denied(()->window.acknowledge(42,8,27,1400));denied(()->window.acknowledge(42,7,23,1400));
        check(window.outstanding()==8);check(window.acknowledge(42,3,12,1400));check(window.outstanding()==4&&window.available(42));
        check(!window.acknowledge(42,3,12,1500));check(!window.acknowledge(42,1,6,1500));denied(()->window.acknowledge(42,3,11,1500));
        check(window.completedFrame()==12&&window.issuedFrame()==24);check(!window.expired(42,181400));check(window.expired(42,181401));
        check(window.acknowledge(42,7,24,181401));check(window.outstanding()==0&&!window.expired(42,999999));
        window.issued(42,8,24,1000000);check(window.acknowledge(42,8,24,1000001));check(window.outstanding()==0); // count=0 still completes.
        window.reset(43,90,1000100);check(window.completedFrame()==90&&window.issuedFrame()==90&&window.outstanding()==0);
        denied(()->window.acknowledge(42,8,24,1000100));denied(()->window.issued(43,1,93,1000100));window.issued(43,0,93,1000100);check(window.acknowledge(43,0,93,1000101));
        // A host that completes only every fourth server tick can never accumulate an unbounded executor queue.
        window.reset(44,0,2000000);long nextTick=0,completedTick=-1,frames=0;
        for(int wallTick=0;wallTick<480;wallTick++){
            if(wallTick%4==0&&completedTick+1<nextTick){completedTick++;window.acknowledge(44,completedTick,(completedTick+1)*3,2000000+wallTick*50);}
            if(window.available(44)){window.issued(44,nextTick,frames+3,2000000+wallTick*50);nextTick++;frames+=3;}
            if(window.outstanding()>8)throw new AssertionError("Unbounded slow-host backlog");
        }
        check(nextTick<480&&nextTick==completedTick+1+window.outstanding());check(frames==nextTick*3);
        while(completedTick+1<nextTick){completedTick++;window.acknowledge(44,completedTick,(completedTick+1)*3,2024000);}
        check(window.outstanding()==0&&window.completedFrame()==frames);final long futureTick=nextTick,futureFrame=frames+3;denied(()->window.acknowledge(44,futureTick,futureFrame,2024001));
        System.out.println("FrameFlowWindow checks="+checks);
    }
}
