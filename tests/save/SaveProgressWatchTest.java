package net.muxigame.console.tests.save;

import net.muxigame.console.domain.DomainException;
import net.muxigame.console.domain.SaveProgressWatch;

/** Timing boundaries test behavior, including attempts to renew a stalled checkpoint indefinitely. */
public final class SaveProgressWatchTest {
    private static int checks;
    public static void main(String[] args){
        var serial=new SaveProgressWatch(0);check(!serial.isSerializing(0),"unobserved watch cannot pause authoritative frames");check(serial.observe("serializing",0,0,0),"first serialization heartbeat accepted");check(serial.isSerializing(0),"accepted serializing stage holds a frame barrier");check(!serial.observe("serializing",0,0,999),"subsecond heartbeat cannot refresh room");check(serial.observe("serializing",0,0,1000),"one-second heartbeat allowed without inventing bytes");
        check(!serial.expired(119999),"long native serialization has its full 120 second window");check(serial.expired(120000),"repeating same transaction does not extend serialization deadline");check(!serial.isSerializing(120000),"expired serialization releases the frame barrier");denied(()->serial.observe("profile",0,100,120000),"late phase transition cannot revive expired serialization");
        var preparing=new SaveProgressWatch(0);check(preparing.observe("serializing",1024,0,0),"preparing can report actual raw bytes before a total is known");check(preparing.observe("serializing",2048,0,1000),"unknown-total serialization bytes remain monotonic");check(preparing.expired(120000),"real preparation bytes still cannot extend absolute serialization window");denied(()->new SaveProgressWatch(0).observe("serializing",SaveProgressWatch.MAX_PREPARE_BYTES+1,0,0),"unknown-total preparation bytes are bounded");
        var transfer=new SaveProgressWatch(0);transfer.observe("serializing",0,0,0);check(transfer.observe("profile",0,100,1000),"native files can follow serialization");
        check(!transfer.isSerializing(1000),"profile transfer releases serialization pause");check(transfer.observe("profile",30,100,2000),"actual transferred bytes advance idle deadline");check(transfer.observe("profile",30,100,61000),"heartbeat may occur before idle boundary");check(transfer.expired(62000),"unchanged bytes do not renew 60 second idle budget");
        var live=new SaveProgressWatch(0);live.observe("serializing",0,0,0);live.observe("profile",0,1000,1000);for(int n=1;n<=15;n++)check(live.observe("profile",n,1000,n*59000L),"real slow progress remains eligible within absolute budget");check(live.expired(900000),"even actual progress cannot exceed 15 minutes");
        var phases=new SaveProgressWatch(0);phases.observe("serializing",0,0,0);phases.observe("profile",100,100,1000);check(phases.observe("state",0,200,2000),"state begins with its own byte counter");
        denied(()->phases.observe("profile",100,100,3000),"phase cannot go backwards");denied(()->phases.observe("state",0,201,3000),"declared phase length cannot keep changing");phases.observe("state",10,200,3000);denied(()->phases.observe("state",9,200,4000),"bytes cannot go backwards");
        denied(()->phases.observe("state",20,200,2999),"clock cannot go backwards");denied(()->new SaveProgressWatch(0).observe("state",0,0,0),"known transfer phase needs a real positive size");denied(()->new SaveProgressWatch(0).observe("state",2,1,0),"over-total bytes rejected");denied(()->new SaveProgressWatch(0).observe("state",0,SaveProgressWatch.MAX_BYTES+1,0),"oversize state claim rejected");denied(()->new SaveProgressWatch(0).observe("other",0,0,0),"unknown phase rejected");
        var legacy=new SaveProgressWatch(0);legacy.observe("serializing",0,0,0);check(legacy.observe("state",0,32,1000),"old GB/GBA can skip native-profile phase");
        System.out.println("SaveProgressWatchTest: "+checks+" checks passed");
    }
    private static void denied(Runnable action,String label){try{action.run();throw new AssertionError(label);}catch(DomainException expected){checks++;}}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
}
