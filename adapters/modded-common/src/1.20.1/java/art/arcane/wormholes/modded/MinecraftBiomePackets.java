package art.arcane.wormholes.modded;

import art.arcane.optics.fidelity.BiomeClaimSet;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.ArrayList;
import java.util.List;

public final class MinecraftBiomePackets {
    private MinecraftBiomePackets() {
    }

    public static ClientboundChunksBiomesPacket packet(Registry<Biome> biomes, List<BiomeClaimSet.ChunkBiomes> chunks) {
        ArrayList<ClientboundChunksBiomesPacket.ChunkBiomeData> columns = new ArrayList<>(chunks.size());
        for (BiomeClaimSet.ChunkBiomes chunk : chunks) {
            columns.add(column(biomes, chunk));
        }
        return new ClientboundChunksBiomesPacket(columns);
    }

    private static ClientboundChunksBiomesPacket.ChunkBiomeData column(Registry<Biome> biomes, BiomeClaimSet.ChunkBiomes chunk) {
        IdMap<Holder<Biome>> ids = biomes.asHolderIdMap();
        Holder<Biome> fallback = biomes.getHolderOrThrow(Biomes.PLAINS);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (int[] section : chunk.sections()) {
                PalettedContainer<Holder<Biome>> palette = new PalettedContainer<>(ids, fallback, PalettedContainer.Strategy.SECTION_BIOMES);
                for (int index = 0; index < section.length; index++) {
                    Holder<Biome> biome = ids.byId(section[index]);
                    palette.set(index & 3, (index >> 4) & 3, (index >> 2) & 3, biome == null ? fallback : biome);
                }
                palette.write(buffer);
            }
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return new ClientboundChunksBiomesPacket.ChunkBiomeData(new ChunkPos(chunk.chunkX(), chunk.chunkZ()), bytes);
        } finally {
            buffer.release();
        }
    }
}
