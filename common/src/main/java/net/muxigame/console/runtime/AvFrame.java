package net.muxigame.console.runtime;

/** Packed RGBA8, top row first; signed PCM16 stereo pairs. Rotation is counterclockwise quarter turns. */
public record AvFrame(long number, int width, int height, int rotation, byte[] rgba,
                      short[] stereoPcm, double sampleRate, boolean shutdown) {
    public AvFrame {
        if (width < 0 || height < 0 || rotation < 0 || rotation > 3 ||
                (long) width * height * 4 != rgba.length || (stereoPcm.length & 1) != 0)
            throw new IllegalArgumentException("Invalid audio/video frame");
    }
}
