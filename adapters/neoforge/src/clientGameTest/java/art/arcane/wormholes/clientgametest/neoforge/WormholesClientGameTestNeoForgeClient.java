package art.arcane.wormholes.clientgametest.neoforge;

import art.arcane.wormholes.clientgametest.ClientGameTestDriver;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = "wormholes_clientgametest", dist = Dist.CLIENT)
public final class WormholesClientGameTestNeoForgeClient {
    public WormholesClientGameTestNeoForgeClient() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> ClientGameTestDriver.clientTick(Minecraft.getInstance()));
    }
}
