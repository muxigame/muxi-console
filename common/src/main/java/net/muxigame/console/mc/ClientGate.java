package net.muxigame.console.mc;

import java.util.function.Consumer;

/** Dedicated servers never link client-only Minecraft classes. */
public final class ClientGate {
    public static Consumer<Wire.Message> receiver=message -> {};
    private ClientGate() {}
}
