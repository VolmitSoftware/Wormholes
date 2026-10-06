package art.arcane.wormholes.modded;

import art.arcane.optics.fidelity.BiomeClaimSet;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftBiomePacketsTest {
    @Test
    @SuppressWarnings("unchecked")
    public void nativeBiomePacketPreservesEverySectionAndQuartCoordinate() {
        Holder.Reference<Biome> local = mock(Holder.Reference.class);
        Holder.Reference<Biome> projected = mock(Holder.Reference.class);
        IdMapper<Holder<Biome>> ids = new IdMapper<>();
        ids.add(local);
        ids.add(projected);
        Registry<Biome> registry = mock(Registry.class);
        when(registry.asHolderIdMap()).thenReturn(ids);
        when(registry.getOrThrow(Biomes.PLAINS)).thenReturn(local);
        int[][] sections = new int[2][64];
        sections[0][3 | (2 << 2) | (1 << 4)] = 1;
        sections[1][0] = 1;
        ClientboundChunksBiomesPacket packet = MinecraftBiomePackets.packet(registry,
            List.of(new BiomeClaimSet.ChunkBiomes(-3, 8, sections)));
        ClientboundChunksBiomesPacket.ChunkBiomeData column = packet.chunkBiomeData().getFirst();
        assertEquals(-3, column.pos().x());
        assertEquals(8, column.pos().z());
        FriendlyByteBuf buffer = column.getReadBuffer();
        try {
            PalettedContainer<Holder<Biome>> palette = new PalettedContainer<>(local, Strategy.createForBiomes(ids));
            for (int[] section : sections) {
                palette.read(buffer);
                for (int index = 0; index < 64; index++) {
                    assertSame(section[index] == 0 ? local : projected,
                        palette.get(index & 3, (index >> 4) & 3, (index >> 2) & 3));
                }
            }
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }
}
