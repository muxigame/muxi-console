package net.muxigame.console.runtime;

import com.sun.jna.*;
import java.util.Map;

/** Invoked as a separate, bounded Java process. Loading exports is not game compatibility. */
public final class CoreProbe {
    public static void main(String[] args) {
        LibretroCore.Api api = Native.load(args[0], LibretroCore.Api.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
        if (api.retro_api_version() != 1) throw new AssertionError("Unsupported public ABI version");
        LibretroCore.SystemInfo info = new LibretroCore.SystemInfo(); api.retro_get_system_info(info); info.read();
        String name = info.library_name.getString(0, "UTF-8");
        if (name.isBlank()) throw new AssertionError("Missing core identity");
        String version = info.library_version == null ? "" : info.library_version.getString(0, "UTF-8");
        System.out.println(name.replace('\n', ' ') + "\t" + version.replace('\n', ' ') + "\tapi=1\tpid=" + ProcessHandle.current().pid());
    }
}
