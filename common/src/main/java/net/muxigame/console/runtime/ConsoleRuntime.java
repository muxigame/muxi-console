package net.muxigame.console.runtime;

import java.io.IOException;

/** Public Java-only boundary. Native libraries are loaded exclusively by the worker. */
public interface ConsoleRuntime extends AutoCloseable {
    CoreInfo info();
    AvFrame step(InputFrame input) throws IOException;
    void reset() throws IOException;
    /** Opaque envelope containing core state, cartridge SRAM and RTC memory. */
    byte[] saveState() throws IOException;
    void loadState(byte[] state) throws IOException;
    byte[] readSram() throws IOException;
    void writeSram(byte[] sram) throws IOException;
    @Override void close() throws IOException;
}
