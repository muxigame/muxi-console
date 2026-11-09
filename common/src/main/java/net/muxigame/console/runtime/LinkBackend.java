package net.muxigame.console.runtime;

import java.io.IOException;

/** Worker-only native endpoint boundary; the Minecraft-side runtime never loads a core. */
interface LinkBackend extends AutoCloseable {
    CoreInfo info();
    LinkRuntime.Frame step(PortInput left,PortInput right)throws IOException;
    byte[] exportEndpoint(int endpoint)throws IOException;
    void restoreEndpoint(int endpoint,byte[] state)throws IOException;
    byte[] saveLinkState()throws IOException;
    void loadLinkState(byte[] state)throws IOException;
    @Override void close()throws IOException;
}
