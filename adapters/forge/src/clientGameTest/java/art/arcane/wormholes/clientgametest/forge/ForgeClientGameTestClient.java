package art.arcane.wormholes.clientgametest.forge;

import art.arcane.wormholes.clientgametest.ClientGameTestDriver;
import net.minecraft.client.Minecraft;
import net.minecraftforge.event.TickEvent;

final class ForgeClientGameTestClient {
    private ForgeClientGameTestClient() {
    }

    static void initialize() {
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> ClientGameTestDriver.clientTick(Minecraft.getInstance()));
    }
}
