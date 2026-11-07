package net.muxigame.console.runtime;

public record CoreInfo(String name, String version, int width, int height, double fps, double sampleRate, double displayAspect) {
    public CoreInfo { displayAspect = AvFrame.resolveAspect(displayAspect, width, height); }
    public CoreInfo(String name, String version, int width, int height, double fps, double sampleRate) {
        this(name, version, width, height, fps, sampleRate, 0);
    }
}
