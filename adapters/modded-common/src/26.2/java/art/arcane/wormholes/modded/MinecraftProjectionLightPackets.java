package art.arcane.wormholes.modded;

import art.arcane.optics.light.LightOverlay;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;

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
            arrays(buffer, light.skyArrays());
            arrays(buffer, light.blockArrays());
            return ClientboundLightUpdatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static void arrays(FriendlyByteBuf buffer, byte[][] arrays) {
        buffer.writeVarInt(arrays.length);
        for (byte[] array : arrays) {
            buffer.writeByteArray(array);
        }
    }
}
