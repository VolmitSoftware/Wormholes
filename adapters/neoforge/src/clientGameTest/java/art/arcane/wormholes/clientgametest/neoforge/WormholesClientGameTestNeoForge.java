package art.arcane.wormholes.clientgametest.neoforge;

import art.arcane.wormholes.clientgametest.SeamlessQaCommand;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod("wormholes_clientgametest")
public final class WormholesClientGameTestNeoForge {
    public WormholesClientGameTestNeoForge() {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> SeamlessQaCommand.register(event.getDispatcher()));
    }
}
