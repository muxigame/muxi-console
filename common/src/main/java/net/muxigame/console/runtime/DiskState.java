package net.muxigame.console.runtime;

/** A disk side is one media image in the core's public disk-control interface. */
public record DiskState(int count, int index, boolean ejected) {
    public DiskState {
        if (count < 0 || count > 256 || index < 0 || index > count) throw new IllegalArgumentException("Invalid disk state");
    }
}
