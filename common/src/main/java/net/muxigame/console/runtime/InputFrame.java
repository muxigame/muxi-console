package net.muxigame.console.runtime;

import java.util.List;

public record InputFrame(List<PortInput> ports) {
    public static final int MAX_PORTS = 4;
    public InputFrame {
        ports = List.copyOf(ports);
        if (ports.size() > MAX_PORTS) throw new IllegalArgumentException("At most four controller ports");
    }
    public static InputFrame idle() { return new InputFrame(List.of()); }
    public PortInput port(int index) { return index >= 0 && index < ports.size() ? ports.get(index) : PortInput.IDLE; }
}
