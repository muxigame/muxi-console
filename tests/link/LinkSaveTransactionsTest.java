package net.muxigame.console.tests.link;

import java.util.UUID;
import net.muxigame.console.domain.*;

/** Exact DS outer transaction terminal fences used by the MC helper before touching its current upload. */
public final class LinkSaveTransactionsTest {
    private static int checks;
    private static LinkSaveTransactions.Key key(long room,long epoch,long actor,long transaction){return new LinkSaveTransactions.Key(new UUID(0,room),epoch,new UUID(0,actor),new UUID(0,transaction));}
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    public static void main(String[] args){
        var history=new LinkSaveTransactions();var a=key(1,2,3,4);var b=key(1,2,3,5);
        check(history.begin(a,10),"first profile_begin reserves the actual outer transaction");history.finish(a,SaveTransactionTombstones.Outcome.COMPLETED,20);
        check(history.terminal(a,21),"commit ACK retires outer transaction before clearing room.dsSave");check(!history.begin(a,21),"late profile_begin after successful group cannot reconstruct a paused save");
        check(history.begin(b,22),"next checkpoint on the same live epoch starts immediately");history.finish(a,SaveTransactionTombstones.Outcome.CANCELLED,23);
        check(history.terminal(a,24)&&!history.terminal(b,24),"late old abort or commit is consumed without touching next transaction");
        history.finish(b,SaveTransactionTombstones.Outcome.CANCELLED,25);check(!history.begin(b,26),"cancelled profile preparation is not recreated by a delayed begin");
        var beforeBegin=key(1,2,3,6);history.finish(beforeBegin,SaveTransactionTombstones.Outcome.CANCELLED,30);check(!history.begin(beforeBegin,31),"abort arriving before its begin also closes the exact transaction");
        check(history.begin(key(2,2,3,4),40),"another room is separate");check(history.begin(key(1,3,3,4),40),"another epoch is separate");check(history.begin(key(1,2,7,4),40),"another host actor is separate");
        check(history.terminal(a,300020),"old transaction survives at least the entire 300-second closing window");check(!history.terminal(a,300021),"bounded terminal history expires only after protection");
        var bounded=new LinkSaveTransactions(2);var c=key(1,1,1,1);var d=key(1,1,1,2);bounded.begin(c,0);bounded.begin(d,0);bounded.finish(c,SaveTransactionTombstones.Outcome.COMPLETED,1);bounded.finish(d,SaveTransactionTombstones.Outcome.CANCELLED,1);
        try{bounded.begin(key(1,1,1,3),2);throw new AssertionError("protected capacity must fail closed");}catch(DomainException expected){checks++;}
        check(bounded.terminal(c,2)&&bounded.terminal(d,2),"capacity pressure never evicts live terminal fences");
        check(!LinkSaveTransactions.finalFrameInRange(0,1018,1003),"frame zero cannot bypass a final save after actual CPU progress");
        check(!LinkSaveTransactions.finalFrameInRange(1000,1018,1003),"old committed group cannot end a newer completed session unchanged");
        check(LinkSaveTransactions.finalFrameInRange(1003,1018,1003),"an exact acknowledged checkpoint may close unchanged");
        check(LinkSaveTransactions.finalFrameInRange(1018,1018,1003),"closing may drain issued batches before capturing its final barrier");
        check(!LinkSaveTransactions.finalFrameInRange(1019,1018,1003),"an unsent final frame has no authority");
        check(LinkSaveTransactions.finalFrameInRange(0,0,0),"a truly unexecuted fresh session can still close without inventing a save");
        System.out.println("LinkSaveTransactionsTest "+checks+" checks passed");
    }
}
