package net.muxigame.console.runtime;

import java.io.IOException;
import java.nio.file.*;

/** Public Java-only boundary. Native libraries are loaded exclusively by the worker. */
public interface ConsoleRuntime extends AutoCloseable {
    CoreInfo info();
    AvFrame step(InputFrame input) throws IOException;
    void reset() throws IOException;
    /** Opaque envelope containing core state, cartridge SRAM and RTC memory. */
    byte[] saveState() throws IOException;
    void loadState(byte[] state) throws IOException;
    /** Large states stay in local spool files, outside the worker socket payload. */
    default void saveStateTo(Path path)throws IOException { Files.write(path,saveState(),StandardOpenOption.CREATE_NEW); }
    default void loadStateFrom(Path path)throws IOException { if(Files.size(path)>64L*1024*1024)throw new IOException("State requires streaming runtime support");loadState(Files.readAllBytes(path)); }
    byte[] readSram() throws IOException;
    void writeSram(byte[] sram) throws IOException;
    default DiskState diskState() throws IOException { return new DiskState(0, 0, true); }
    default void selectDisk(int index) throws IOException { throw new IOException("Core does not support disk selection"); }
    default void setDiskEjected(boolean ejected) throws IOException { throw new IOException("Core does not support disk eject"); }
    @Override void close() throws IOException;
}
