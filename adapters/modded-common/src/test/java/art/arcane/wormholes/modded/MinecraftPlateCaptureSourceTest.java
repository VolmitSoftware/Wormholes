package art.arcane.wormholes.modded;

import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.chunk.ChunkLeaseRegistry;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.plate.PlateCaptureJob;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftPlateCaptureSourceTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void airSectionsStayNullAndOccupiedSectionsAreCopied() {
        Fixture fixture = fixture();
        PalettedContainer<BlockState> states = container();
        states.set(1, 2, 3, stone());
        LevelChunkSection air = mock(LevelChunkSection.class);
        when(air.hasOnlyAir()).thenReturn(true);
        LevelChunkSection filled = mock(LevelChunkSection.class);
        when(filled.hasOnlyAir()).thenReturn(false);
        when(filled.getStates()).thenReturn(states);
        when(fixture.chunk().getSectionsCount()).thenReturn(2);
        when(fixture.chunk().getMinSectionY()).thenReturn(-4);
        when(fixture.chunk().getSection(0)).thenReturn(air);
        when(fixture.chunk().getSection(1)).thenReturn(filled);
        MinecraftPlateCaptureSource.CapturedChunk captured = new MinecraftPlateCaptureSource(fixture.runtime(), fixture.worldId(), false)
            .capture(fixture.level(), 0, 0);
        assertEquals(-4, captured.minSectionY());
        assertEquals(2, captured.sections().length);
        assertNull(captured.sections()[0]);
        assertNotSame(states, captured.sections()[1]);
        assertSame(stone(), captured.sections()[1].get(1, 2, 3));
        assertSame(air(), captured.sections()[1].get(0, 0, 0));
        states.set(1, 2, 3, air());
        assertSame(stone(), captured.sections()[1].get(1, 2, 3));
        assertTrue(captured.blockEntities().isEmpty());
        assertTrue(captured.blockEntitiesComplete());
    }

    @Test
    public void blockEntitiesAreCappedPerChunkAndReportedIncomplete() {
        List<String> types = FidelitySettings.blockEntityTypes;
        FidelitySettings.blockEntityTypes = List.of("minecraft:sign");
        try {
            Fixture fixture = fixture();
            when(fixture.chunk().getSectionsCount()).thenReturn(0);
            Map<BlockPos, BlockEntity> busy = new LinkedHashMap<>();
            int count = PlateCaptureJob.MAX_BLOCK_ENTITIES_PER_CHUNK + 16;
            for (int index = 0; index < count; index++) {
                BlockPos position = new BlockPos(index & 15, 64 + (index >> 4), 3);
                busy.put(position, sign(position));
            }
            when(fixture.chunk().getBlockEntities()).thenReturn(busy);
            MinecraftPlateCaptureSource.CapturedChunk capped = new MinecraftPlateCaptureSource(fixture.runtime(), fixture.worldId(), true)
                .capture(fixture.level(), 0, 0);
            assertEquals(PlateCaptureJob.MAX_BLOCK_ENTITIES_PER_CHUNK, capped.blockEntities().size());
            assertFalse(capped.blockEntitiesComplete());
            BlockPos first = new BlockPos(1, 64, 3);
            Map<BlockPos, BlockEntity> quiet = Map.of(first, sign(first));
            when(fixture.chunk().getBlockEntities()).thenReturn(quiet);
            MinecraftPlateCaptureSource.CapturedChunk complete = new MinecraftPlateCaptureSource(fixture.runtime(), fixture.worldId(), true)
                .capture(fixture.level(), 0, 0);
            assertEquals(1, complete.blockEntities().size());
            assertEquals("minecraft:sign", complete.blockEntities().get(ProjectionCellKey.pack(1, 64, 3)).typeKey());
            assertTrue(complete.blockEntitiesComplete());
            MinecraftPlateCaptureSource.CapturedChunk skipped = new MinecraftPlateCaptureSource(fixture.runtime(), fixture.worldId(), false)
                .capture(fixture.level(), 0, 0);
            assertTrue(skipped.blockEntities().isEmpty());
        } finally {
            FidelitySettings.blockEntityTypes = types;
        }
    }

    @Test
    public void holdsFollowTheLeaseAndLoadedReadsTheChunkCache() {
        Fixture fixture = fixture();
        MinecraftPlateCaptureSource source = new MinecraftPlateCaptureSource(fixture.runtime(), fixture.worldId(), false);
        assertTrue(source.loaded(fixture.level(), 0, 0));
        assertFalse(source.loaded(fixture.level(), 7, 7));
        PlateCaptureJob.Hold hold = source.hold(fixture.level(), 7, 7);
        verify(fixture.leases()).retain(fixture.level(), fixture.worldId(), 7, 7);
        assertFalse(hold.settled());
        assertFalse(hold.ready());
        fixture.ready().complete(true);
        assertTrue(hold.settled());
        assertTrue(hold.ready());
        hold.release();
        verify(fixture.lease()).close();
    }

    @Test
    public void captureRequiresTheServerThreadAndALoadedChunk() {
        Fixture fixture = fixture();
        MinecraftPlateCaptureSource source = new MinecraftPlateCaptureSource(fixture.runtime(), fixture.worldId(), false);
        assertThrows(IllegalStateException.class, () -> source.capture(fixture.level(), 7, 7));
        doThrow(new IllegalStateException("off thread")).when(fixture.runtime()).requireServerThread();
        assertThrows(IllegalStateException.class, () -> source.capture(fixture.level(), 0, 0));
    }

    static PalettedContainer<BlockState> container() {
        return new PalettedContainer<>(air(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
    }

    private static BlockEntity sign(BlockPos position) {
        BlockEntity entity = mock(BlockEntity.class);
        doReturn(BlockEntityTypes.SIGN).when(entity).getType();
        when(entity.getBlockPos()).thenReturn(position);
        CompoundTag tag = new CompoundTag();
        tag.putString("front_text", "hello");
        when(entity.saveWithFullMetadata(nullable(HolderLookup.Provider.class))).thenReturn(tag);
        return entity;
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);
        ChunkLeaseRegistry<ServerLevel> leases = mock(ChunkLeaseRegistry.class);
        ChunkLease lease = mock(ChunkLease.class);
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        UUID worldId = UUID.randomUUID();
        when(runtime.leases()).thenReturn(leases);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunkNow(0, 0)).thenReturn(chunk);
        when(chunk.getBlockEntities()).thenReturn(Map.of());
        when(leases.retain(any(), any(), anyInt(), anyInt())).thenReturn(lease);
        when(lease.ready()).thenReturn(ready);
        return new Fixture(runtime, level, chunk, leases, lease, ready, worldId);
    }

    private record Fixture(WormholesModRuntime runtime, ServerLevel level, LevelChunk chunk, ChunkLeaseRegistry<ServerLevel> leases,
                           ChunkLease lease, CompletableFuture<Boolean> ready, UUID worldId) {
    }

    private static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }
}
