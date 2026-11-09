package net.muxigame.console.tests.save;

import java.util.UUID;
import net.muxigame.console.domain.*;

/** Regression for state_commit -> late heartbeat -> next checkpoint, without reconstructing the old lock. */
public final class SaveTransactionTombstonesTest {
    private static int checks;
    private record Scope(UUID actor,String room,long epoch,UUID machine,String content,UUID tx){ }
    private static Scope scope(String room,long epoch,int tx){return new Scope(new UUID(0,1),room,epoch,new UUID(0,2),"a".repeat(64),new UUID(0,tx));}
    public static void main(String[] args){
        var history=new SaveTransactionTombstones<Scope>();var old=scope("held/a",1,1);var next=scope("held/a",1,2);
        check(history.reserve(old,0),"original checkpoint owns an active slot");history.retire(old,SaveTransactionTombstones.Outcome.COMPLETED,100);
        check(!history.active(old),"successful commit releases the actor's old active lease");check(!history.reserve(old,101),"heartbeat arriving while client waits commit reply cannot reconstruct old watch");
        check(history.reserve(next,102),"new checkpoint is immediately admissible");history.retire(old,SaveTransactionTombstones.Outcome.CANCELLED,103);
        check(history.active(next),"late old abort cannot clear the new checkpoint");check(history.outcome(old,104).orElseThrow()==SaveTransactionTombstones.Outcome.COMPLETED,"late old abort/commit is idempotent and preserves the completed result");
        for(int i=0;i<3;i++)check(!history.reserve(old,200+i),"replayed completed heartbeat remains retired");
        check(history.reserve(scope("held/b",1,1),500),"same tx UUID in another room is separate");check(history.reserve(scope("held/a",2,1),500),"same tx UUID in another epoch is separate");
        var cancelled=scope("held/c",1,3);history.reserve(cancelled,600);history.retire(cancelled,SaveTransactionTombstones.Outcome.CANCELLED,601);check(!history.reserve(cancelled,602),"cancelled preparation cannot be revived by delayed heartbeat");
        var expired=scope("held/d",1,4);history.reserve(expired,700);history.retire(expired,SaveTransactionTombstones.Outcome.EXPIRED,701);check(!history.reserve(expired,702),"deadline-expired transaction cannot be revived");
        check(history.outcome(old,300100).isPresent(),"completed identity is retained for at least the entire five minutes");check(history.outcome(old,300101).isEmpty(),"history becomes eligible for bounded expiry after retention");
        var bounded=new SaveTransactionTombstones<Scope>(3);Scope a=scope("a",1,1),b=scope("b",1,2),c=scope("c",1,3),d=scope("d",1,4);
        bounded.reserve(a,0);bounded.retire(a,SaveTransactionTombstones.Outcome.COMPLETED,1);bounded.reserve(b,2);bounded.reserve(c,3);
        denied(()->bounded.reserve(d,4),"capacity includes slots reserved by active checkpoints");bounded.retire(b,SaveTransactionTombstones.Outcome.CANCELLED,5);bounded.retire(c,SaveTransactionTombstones.Outcome.EXPIRED,6);
        check(bounded.size(6)==3,"closing an admitted transaction never needs to evict a protected tombstone");denied(()->bounded.reserve(d,7),"full history refuses new admission rather than dropping five-minute protection");
        check(bounded.outcome(a,300001).isPresent(),"capacity pressure does not shorten retention");check(bounded.reserve(d,300002),"expired records free bounded admission slots");
        System.out.println("SaveTransactionTombstonesTest: "+checks+" checks passed");
    }
    private static void denied(Runnable action,String label){try{action.run();throw new AssertionError(label);}catch(DomainException expected){checks++;}}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
}
