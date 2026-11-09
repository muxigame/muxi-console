package net.muxigame.console.tests.save;

import java.util.UUID;
import net.muxigame.console.domain.DomainException;
import net.muxigame.console.domain.HoldSaveBarrier;

/** Background commits, final capture frames, request replacement and explicit clearing. */
public final class HoldSaveBarrierTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void denied(Runnable action, String message) {
        try { action.run(); throw new AssertionError(message); }
        catch (DomainException expected) { checks++; }
    }
    public static void main(String[] args) {
        HoldSaveBarrier barrier = new HoldSaveBarrier();
        UUID first = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
        UUID next = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");
        UUID last = UUID.fromString("cccccccc-1111-2222-3333-444444444444");
        check(barrier.token().isEmpty(), "New barrier has no pending hold");
        check(!barrier.accept("", 300, 300), "Empty auto-save did not satisfy absent hold");
        check(!barrier.accept(null, 300, 300), "Null token did not satisfy absent hold");
        check(barrier.issue(first).equals(first.toString()), "Issued token returned in canonical form");
        check(barrier.token().equals(first.toString()), "Active token retained");
        // The server may still have observed frame 300 while the host already ran to 306.
        check(!barrier.accept("", 300, 300), "Background commit could not complete later join or leave");
        check(!barrier.accept(null, 300, 300), "Missing hold token rejected");
        check(!barrier.accept(next.toString(), 306, 300), "Another request's final save rejected");
        check(!barrier.accept(first.toString(), 299, 300), "Snapshot older than observed progress rejected");
        check(barrier.accept(first.toString(), 306, 300), "Matching final snapshot ahead of observed frame accepted");
        check(barrier.accept(first.toString(), 306, 306), "Exact final frame accepted");
        check(barrier.accept(first.toString(), 306, 306), "Acceptance did not consume current hold");
        check(barrier.token().equals(first.toString()), "Token remains until successful handoff clears it");
        check(!barrier.accept(first.toString(), 306, 307), "Later observation invalidated older capture");
        check(!barrier.accept(first.toString(), -1, 0), "Negative snapshot rejected");
        check(!barrier.accept(first.toString(), 0, -1), "Negative observed frame rejected");
        check(!barrier.accept(first.toString(), -1, -1), "Both negative frames rejected");
        check(barrier.accept(first.toString(), Long.MAX_VALUE, Long.MAX_VALUE), "Exact maximum frame accepted without arithmetic overflow");
        check(barrier.accept(first.toString(), Long.MAX_VALUE, Long.MAX_VALUE - 1), "Newer maximum frame accepted");
        check(!barrier.accept(first.toString(), Long.MAX_VALUE - 1, Long.MAX_VALUE), "Old maximum frame rejected");
        check(!barrier.accept(first.toString().toUpperCase(), 306, 306), "Noncanonical token spelling rejected");
        check(!barrier.accept("not-a-uuid", 306, 306), "Malformed token safely returned false");
        denied(() -> barrier.issue(null), "Null hold request accepted");
        denied(() -> barrier.issue(first), "Active token reused as a fresh request");
        check(barrier.accept(first.toString(), 306, 306), "Invalid issue did not mutate current request");

        check(barrier.issue(next).equals(next.toString()), "New hold request replaced previous generation");
        check(!barrier.accept(first.toString(), Long.MAX_VALUE, 0), "Late previous hold never matched new request");
        check(barrier.accept(next.toString(), 0, 0), "New epoch may start its own frame origin");
        barrier.clear();
        check(barrier.token().isEmpty(), "Completed or cancelled hold cleared");
        check(!barrier.accept(next.toString(), Long.MAX_VALUE, 0), "Late completed hold rejected after clear");
        barrier.clear();
        check(barrier.token().isEmpty(), "Repeated clear is harmless");
        barrier.issue(last);
        check(!barrier.accept(first.toString(), 0, 0) && !barrier.accept(next.toString(), 0, 0), "Earlier requests do not revive after reuse");
        check(barrier.accept(last.toString(), 1, 1), "Barrier can accept a later fresh request");
        System.out.println("HoldSaveBarrier checks=" + checks);
    }
}
