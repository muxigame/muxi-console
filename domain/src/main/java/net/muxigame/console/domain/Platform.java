package net.muxigame.console.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Stable ids and our admission limits, not a claim of hardware or game compatibility. */
public enum Platform {
    NES(64, 4, "nes"), SNES(16, 4, "sfc", "smc"),
    GB(8, 1, "gb", "dmg"), GBC(8, 1, "gbc"), GBA(32, 1, "gba"),
    MD(16, 4, "md", "gen", "bin"), N64(64, 4, "z64", "n64", "v64"),
    SMS(4, 2, "sms"), GG(4, 1, "gg"), SG1000(1, 2, "sg"),
    PCE(8, 4, "pce"), A2600(1, 2, "a26"), A7800(4, 2, "a78"),
    LYNX(2, 1, "lnx", "o"), NGP(8, 1, "ngp"), NGPC(8, 1, "ngc", "ngpc"),
    WS(16, 1, "ws"), WSC(16, 1, "wsc"), VB(16, 1, "vb"),
    FDS(8, 2, "fds"), ARCADE(512, 4, "mxarc"),
    NDS(512,1,"nds"), N3DS(8192,1,"3dsx","3ds","cci","cxi"), WII(9216,4,"dol","elf","iso","wbfs","rvz");

    private final long maxBytes;
    private final int controllerPorts;
    private final Set<String> extensions;

    Platform(int maximumMiB, int controllerPorts, String... extensions) {
        this.maxBytes = maximumMiB * 1024L * 1024L;
        this.controllerPorts = controllerPorts;
        this.extensions = Set.of(extensions);
    }

    public String id() { return name().toLowerCase(Locale.ROOT); }
    public long maxBytes() { return maxBytes; }
    public long minBytes() {
        return switch (this) {
            case NES -> 16 + 16384;
            case SNES, GB, GBC, WS, WSC -> 32768;
            case GBA -> 192;
            case MD -> 512;
            case N64, NGP, NGPC -> 4096;
            case SMS, GG, PCE -> 8192;
            case SG1000 -> 1024;
            case A2600 -> 2048;
            case A7800 -> 4096 + 128;
            case LYNX -> 11;
            case VB -> 16384;
            case FDS -> 65500;
            case ARCADE -> 22;
            case NDS -> 512;
            case N3DS -> 32;
            case WII -> 64;
        };
    }
    public int controllerPorts() { return controllerPorts; }
    /** Every platform uses one authoritative core; remote seats receive video/audio, never a parallel ROM. */
    public boolean sharedRuntime(){return true;}
    public int customModelData() { return switch(this){case NDS->23;case N3DS->24;case WII->25;default->ordinal()+1;}; }
    public Set<String> extensions() { return extensions; }
    public boolean acceptsExtension(String extension) { return extensions.contains(extension.toLowerCase(Locale.ROOT)); }

    public static Platform fromId(String id) {
        if (id == null) throw new DomainException("platform", "A platform is required");
        return Arrays.stream(values()).filter(p -> p.id().equals(id.toLowerCase(Locale.ROOT)))
                .findFirst().orElseThrow(() -> new DomainException("platform", "Unknown platform"));
    }

    public static List<Platform> forExtension(String extension) {
        return Arrays.stream(values()).filter(p -> p.acceptsExtension(extension)).toList();
    }
}
