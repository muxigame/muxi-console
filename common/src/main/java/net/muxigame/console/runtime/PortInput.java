package net.muxigame.console.runtime;

/** Bit order follows the public RetroPad ABI: B,Y,Select,Start,Up,Down,Left,Right,A,X,L,R,L2,R2,L3,R3. */
public record PortInput(int buttons, short leftX, short leftY, short rightX, short rightY) {
    public static final PortInput IDLE = new PortInput(0, (short) 0, (short) 0, (short) 0, (short) 0);
    public PortInput {
        if ((buttons & ~0xffff) != 0) throw new IllegalArgumentException("RetroPad mask exceeds 16 bits");
    }
    public PortInput(int buttons) { this(buttons, (short) 0, (short) 0, (short) 0, (short) 0); }
}
