package net.muxigame.console;

import net.muxigame.console.mc.ConsoleContent;
import net.muxigame.console.mc.ConsoleServer;
import net.muxigame.console.mc.Wire;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MuxiConsole.ID)
public final class MuxiConsole {
    public static final String ID="muxi_console";
    public static final Logger LOG=LoggerFactory.getLogger("Muxi Console");
    public MuxiConsole(IEventBus bus) {
        ConsoleContent.register(bus);
        bus.addListener(Wire::register);
        NeoForge.EVENT_BUS.addListener(ConsoleServer::tick);
        NeoForge.EVENT_BUS.addListener(ConsoleServer::stopped);
        NeoForge.EVENT_BUS.addListener(ConsoleServer::logout);
    }
}
