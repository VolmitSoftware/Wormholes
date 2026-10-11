package art.arcane.wormholes.modded;

import art.arcane.optics.light.LightOverlay;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;

import java.util.List;

public final class MinecraftProjectionLightPackets {
    private MinecraftProjectionLightPackets() {
    }

    public static ClientboundLightUpdatePacket packet(LightOverlay.ChunkLight light) {
        return new ClientboundLightUpdatePacket(light.chunkX(), light.chunkZ(), new ClientboundLightUpdatePacketData(light.skyMask(),
            light.blockMask(), light.emptySkyMask(), light.emptyBlockMask(), List.of(light.skyArrays()), List.of(light.blockArrays())));
    }
}
