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
    default DiskState diskState() throws IOException { return new DiskState(0, 0, true); }
    default void selectDisk(int index) throws IOException { throw new IOException("Core does not support disk selection"); }
    default void setDiskEjected(boolean ejected) throws IOException { throw new IOException("Core does not support disk eject"); }
    @Override void close() throws IOException;
}
