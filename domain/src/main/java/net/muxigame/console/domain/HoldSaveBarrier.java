package net.muxigame.console.domain;

import java.util.UUID;

/** A background snapshot cannot complete a later request to save and change ownership. */
public final class HoldSaveBarrier {
    private String token = "";

    /** The server supplies a fresh UUID for each new hold request. */
    public synchronized String issue(UUID freshToken) {
        if (freshToken == null || freshToken.toString().equals(token))
            throw new DomainException("save_hold", "A new save hold requires a fresh token");
        token = freshToken.toString();
        return token;
    }

    public synchronized String token() { return token; }

    /** Matching is non-consuming; clear only after the caller completes the handoff. */
    public synchronized boolean accept(String incomingToken, long snapshotFrame, long observedFrame) {
        return !token.isEmpty() && token.equals(incomingToken)
                && observedFrame >= 0 && snapshotFrame >= observedFrame;
    }

    public synchronized void clear() { token = ""; }
}
