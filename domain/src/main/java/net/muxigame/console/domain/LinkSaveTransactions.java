package net.muxigame.console.domain;

import java.util.*;

/** Main-thread transaction identities for DS group uploads, including packets delayed after an ACK. */
public final class LinkSaveTransactions {
    public record Key(UUID room,long epoch,UUID actor,UUID transaction) {
        public Key {Objects.requireNonNull(room);Objects.requireNonNull(actor);Objects.requireNonNull(transaction);if(epoch<0)throw new IllegalArgumentException("Invalid link epoch");}
    }
    private final SaveTransactionTombstones<Key> history;
    public LinkSaveTransactions(){history=new SaveTransactionTombstones<>();}
    public LinkSaveTransactions(int capacity){history=new SaveTransactionTombstones<>(capacity);}
    public boolean begin(Key key,long now){return history.reserve(key,now);}
    public boolean terminal(Key key,long now){return history.outcome(key,now).isPresent();}
    public void finish(Key key,SaveTransactionTombstones.Outcome outcome,long now){history.retire(key,outcome,now);}
    /** A final checkpoint may drain issued work, but cannot claim an older frame than the host already ACKed. */
    public static boolean finalFrameInRange(long frame,long issued,long completed){return frame>=0&&completed>=0&&completed<=issued&&frame>=completed&&frame<=issued;}
}
