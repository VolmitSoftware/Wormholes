package art.arcane.wormholes.modded;

import art.arcane.optics.light.LightOverlay;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;

import java.util.List;

public final class MinecraftProjectionLightPackets {
    private MinecraftProjectionLightPackets() {
    }

    public static ClientboundLightUpdatePacket packet(LightOverlay.ChunkLight light) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(light.chunkX());
            buffer.writeVarInt(light.chunkZ());
            buffer.writeBitSet(light.skyMask());
            buffer.writeBitSet(light.blockMask());
            buffer.writeBitSet(light.emptySkyMask());
            buffer.writeBitSet(light.emptyBlockMask());
            buffer.writeCollection(List.of(light.skyArrays()), FriendlyByteBuf::writeByteArray);
            buffer.writeCollection(List.of(light.blockArrays()), FriendlyByteBuf::writeByteArray);
            return new ClientboundLightUpdatePacket(buffer);
        } finally {
            buffer.release();
        }
    }
}
