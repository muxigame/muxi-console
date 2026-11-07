package net.muxigame.console.runtime;

/** Packed RGBA8, top row first; PCM16 stereo pairs. Display aspect precedes the requested quarter-turn rotation. */
public record AvFrame(long number, int width, int height, int rotation, byte[] rgba,
                      short[] stereoPcm, double sampleRate, boolean shutdown, double displayAspect) {
    public AvFrame {
        if (width < 0 || height < 0 || rotation < 0 || rotation > 3 ||
                (long) width * height * 4 != rgba.length || (stereoPcm.length & 1) != 0)
            throw new IllegalArgumentException("Invalid audio/video frame");
        displayAspect = resolveAspect(displayAspect, width, height);
    }
    /** Source/binary compatibility for callers whose images have square pixels. */
    public AvFrame(long number, int width, int height, int rotation, byte[] rgba,
                   short[] stereoPcm, double sampleRate, boolean shutdown) {
        this(number, width, height, rotation, rgba, stereoPcm, sampleRate, shutdown, 0);
    }
    static double resolveAspect(double aspect, int width, int height) {
        if (!Double.isFinite(aspect)) throw new IllegalArgumentException("Invalid display aspect");
        return aspect > 0 ? aspect : width > 0 && height > 0 ? (double) width / height : 1.0;
    }
}
