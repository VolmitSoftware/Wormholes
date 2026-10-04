package art.arcane.wormholes.modded;

import art.arcane.wormholes.network.client.ClientViewProtocol;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
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
            Unpooled.buffer(4096, ClientViewProtocol.MAX_TRAVEL_CHUNK_BYTES), registries);
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
