package art.arcane.wormholes.modded;

import art.arcane.wormholes.network.client.ClientViewProtocol;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Objects;

public final class MinecraftChunkPacketEncoding {
    private MinecraftChunkPacketEncoding() {
    }

    public static byte[] encode(RegistryAccess registries, ClientboundLevelChunkWithLightPacket packet) {
        Objects.requireNonNull(registries, "registries");
        Objects.requireNonNull(packet, "packet");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
            Unpooled.buffer(initialCapacity(packet), ClientViewProtocol.MAX_TRAVEL_CHUNK_BYTES), registries);
        try {
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer, packet);
            if (!canonicalHeightmaps(packet)) {
                ClientboundLevelChunkWithLightPacket normalized = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buffer);
                buffer.clear();
                ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer, normalized);
            }
            byte[] payload = new byte[buffer.readableBytes()];
            buffer.readBytes(payload);
            return payload;
        } finally {
            buffer.release();
        }
    }

    private static int initialCapacity(ClientboundLevelChunkWithLightPacket packet) {
        FriendlyByteBuf chunk = packet.chunkData().getReadBuffer();
        long capacity;
        try {
            capacity = 4096L + chunk.readableBytes();
        } finally {
            chunk.release();
        }
        for (byte[] update : packet.lightData().skyUpdates()) {
            capacity += update.length;
        }
        for (byte[] update : packet.lightData().blockUpdates()) {
            capacity += update.length;
        }
        return (int) Math.min(capacity, ClientViewProtocol.MAX_TRAVEL_CHUNK_BYTES);
    }

    private static boolean canonicalHeightmaps(ClientboundLevelChunkWithLightPacket packet) {
        int previous = -1;
        for (Heightmap.Types type : packet.chunkData().getHeightmaps().keySet()) {
            if (type.ordinal() <= previous) {
                return false;
            }
            previous = type.ordinal();
        }
        return true;
    }
}
