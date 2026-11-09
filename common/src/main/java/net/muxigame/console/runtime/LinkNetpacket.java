package net.muxigame.console.runtime;

import com.sun.jna.Pointer;
import java.io.IOException;

/** Optional worker-only libretro NETPACKET frontend; ordinary cores have no network delegate. */
public interface LinkNetpacket extends AutoCloseable {
    boolean configure(Pointer callbacks)throws IOException;
    default void beforeRun()throws IOException{}
    /** Called on the core thread while the native callback functions are still loaded. */
    @Override default void close()throws IOException{}
}
