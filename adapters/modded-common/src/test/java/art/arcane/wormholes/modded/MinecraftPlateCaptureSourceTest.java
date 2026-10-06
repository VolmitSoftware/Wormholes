package art.arcane.wormholes.modded;

import art.arcane.optics.plate.ChunkLease;
import art.arcane.optics.plate.ChunkLeaseRegistry;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.optics.plate.PlateCaptureJob;
import art.arcane.optics.plate.ViewPlateBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import art.arcane.optics.view.ContentView;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
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
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

public class MinecraftPlateCaptureSourceTest extends MinecraftTestBase {
    @Test
    public void metadataOnlyChangeRecapturesCurrentBlockEntityWithinTheRetentionWindow() {
        List<String> types = FidelitySettings.blockEntityTypes;
        FidelitySettings.blockEntityTypes = List.of("minecraft:sign");
        try {
            Fixture fixture = fixture();
            BlockPos position = new BlockPos(1, 64, 3);
            BlockEntity entity = sign(position);
            when(fixture.chunk().getBlockEntities()).thenReturn(Map.of(position, entity));
            MinecraftPlateCaptureSource source = new MinecraftPlateCaptureSource(fixture.runtime(),
                new MinecraftPlateCaptureSource.Options(fixture.worldId(), true, 64, 79, false));
            MinecraftPlateCaptureSource.CapturedChunk before = source.capture(fixture.level(), 0, 0);
            when(fixture.level().getGameTime()).thenReturn(1000L);
            assertSame(before, source.cached(fixture.level(), 0, 0));
            CompoundTag changed = new CompoundTag();
            changed.putString("front_text", "changed");
            when(entity.saveWithFullMetadata(nullable(HolderLookup.Provider.class))).thenReturn(changed);
            fixture.changes().markChanged(fixture.worldId(), position.getX(), position.getY(), position.getZ());
            assertNull(source.cached(fixture.level(), 0, 0));
            MinecraftPlateCaptureSource.CapturedChunk after = source.capture(fixture.level(), 0, 0);
            long cell = CellKeys.pack(1, 64, 3);
            assertNotEquals(before.blockEntities().get(cell), after.blockEntities().get(cell));
            assertSame(after, source.cached(fixture.level(), 0, 0));
        } finally {
            FidelitySettings.blockEntityTypes = types;
        }
    }

    @Test
    public void meshMetadataIsCopiedBeforeTheLeasedChunkCanUnload() {
        Fixture fixture = fixture();
        LevelChunkSection section = mock(LevelChunkSection.class);
        Holder.Reference<Biome> biome = mock(Holder.Reference.class);
        when(biome.unwrapKey()).thenReturn(Optional.of(Biomes.PLAINS));
        when(section.getNoiseBiome(anyInt(), anyInt(), anyInt())).thenReturn(biome);
        Holder.Reference<Biome> topBiome = mock(Holder.Reference.class);
        when(topBiome.unwrapKey()).thenReturn(Optional.of(Biomes.DESERT));
        when(section.getNoiseBiome(anyInt(), eq(3), anyInt())).thenReturn(topBiome);
        when(section.hasOnlyAir()).thenReturn(true);
        when(fixture.chunk().getMinSectionY()).thenReturn(0);
        when(fixture.chunk().getSectionsCount()).thenReturn(1);
        when(fixture.chunk().getSection(0)).thenReturn(section);
        when(fixture.level().getMinSectionY()).thenReturn(0);
        when(fixture.level().getMaxSectionY()).thenReturn(0);
        LevelLightEngine engine = mock(LevelLightEngine.class);
        LayerLightEventListener block = mock(LayerLightEventListener.class);
        LayerLightEventListener sky = mock(LayerLightEventListener.class);
        DataLayer blockLayer = new DataLayer(4);
        DataLayer skyLayer = new DataLayer(13);
        when(engine.getLayerListener(LightLayer.BLOCK)).thenReturn(block);
        when(engine.getLayerListener(LightLayer.SKY)).thenReturn(sky);
        when(block.getDataLayerData(any())).thenReturn(blockLayer);
        when(sky.getDataLayerData(any())).thenReturn(skyLayer);
        when(fixture.level().getLightEngine()).thenReturn(engine);
        DimensionType dimension = mock(DimensionType.class);
        when(dimension.hasSkyLight()).thenReturn(true);
        when(fixture.level().dimensionType()).thenReturn(dimension);
        MinecraftPlateCaptureSource source = new MinecraftPlateCaptureSource(fixture.runtime(),
            new MinecraftPlateCaptureSource.Options(fixture.worldId(), false, -8, 31, true));
        MinecraftPlateCaptureSource.CapturedChunk captured = source.capture(fixture.level(), 0, 0);
        when(fixture.level().getChunkSource().getChunkNow(0, 0)).thenReturn(null);
        blockLayer.set(1, 2, 3, 0);
        skyLayer.set(1, 2, 3, 0);
        assertEquals("minecraft:plains", captured.biomes()[0][0]);
        assertEquals(ContentView.packLight(13, 4), captured.light().light(1, 2, 3));
        assertEquals(ContentView.packLight(15, 0), captured.light().light(1, 25, 3));
        Long2ObjectOpenHashMap<MinecraftPlateCaptureSource.CapturedChunk> chunks = new Long2ObjectOpenHashMap<>();
        chunks.put(0L, captured);
        MinecraftCapturedChunkView view = new MinecraftCapturedChunkView(fixture.worldId(), 0, 16, 1,
            new PlateCaptureJob.Captured<>(new ViewPlateBuilder.Footprint(0, 0, 0, 0, 0), chunks));
        assertEquals("minecraft:plains", view.sampleBiome(1, -8, 3));
        assertEquals("minecraft:desert", view.sampleBiome(1, 23, 3));
        verify(fixture.runtime()).requireServerThread();
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
        MinecraftPlateCaptureSource.CapturedChunk captured = new MinecraftPlateCaptureSource(fixture.runtime(), MinecraftPlateCaptureSource.Options.column(fixture.worldId(), false))
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
    public void sectionCaptureCopiesOnlyIntersectingNegativeHeightPalettes() {
        Fixture fixture = fixture();
        LevelChunkSection filled = mock(LevelChunkSection.class);
        when(filled.getStates()).thenReturn(container());
        when(fixture.chunk().getMinSectionY()).thenReturn(-4);
        when(fixture.chunk().getSectionsCount()).thenReturn(24);
        when(fixture.chunk().getSection(2)).thenReturn(filled);
        when(fixture.chunk().getSection(3)).thenReturn(filled);
        MinecraftPlateCaptureSource.Options options = new MinecraftPlateCaptureSource.Options(fixture.worldId(), false, -17, -2, false);
        MinecraftPlateCaptureSource.CapturedChunk captured = new MinecraftPlateCaptureSource(fixture.runtime(), options)
            .capture(fixture.level(), 0, 0);
        assertEquals(-2, captured.minSectionY());
        assertEquals(2, captured.sections().length);
        verify(fixture.chunk(), never()).getSection(0);
        verify(fixture.chunk(), never()).getSection(1);
        verify(fixture.chunk(), never()).getSection(4);
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
            MinecraftPlateCaptureSource.CapturedChunk capped = new MinecraftPlateCaptureSource(fixture.runtime(), MinecraftPlateCaptureSource.Options.column(fixture.worldId(), true))
                .capture(fixture.level(), 0, 0);
            assertEquals(PlateCaptureJob.MAX_BLOCK_ENTITIES_PER_CHUNK, capped.blockEntities().size());
            assertFalse(capped.blockEntitiesComplete());
            BlockPos first = new BlockPos(1, 64, 3);
            Map<BlockPos, BlockEntity> quiet = Map.of(first, sign(first));
            when(fixture.chunk().getBlockEntities()).thenReturn(quiet);
            MinecraftPlateCaptureSource.CapturedChunk complete = new MinecraftPlateCaptureSource(fixture.runtime(), MinecraftPlateCaptureSource.Options.column(fixture.worldId(), true))
                .capture(fixture.level(), 0, 0);
            assertEquals(1, complete.blockEntities().size());
            assertEquals("minecraft:sign", complete.blockEntities().get(CellKeys.pack(1, 64, 3)).typeKey());
            assertTrue(complete.blockEntitiesComplete());
            MinecraftPlateCaptureSource.CapturedChunk skipped = new MinecraftPlateCaptureSource(fixture.runtime(), MinecraftPlateCaptureSource.Options.column(fixture.worldId(), false))
                .capture(fixture.level(), 0, 0);
            assertTrue(skipped.blockEntities().isEmpty());
        } finally {
            FidelitySettings.blockEntityTypes = types;
        }
    }

    @Test
    public void blockEntitiesOutsideTheCapturedHeightDoNotConsumeItsLimit() {
        List<String> types = FidelitySettings.blockEntityTypes;
        FidelitySettings.blockEntityTypes = List.of("minecraft:sign");
        try {
            Fixture fixture = fixture();
            Map<BlockPos, BlockEntity> entities = new LinkedHashMap<>();
            for (int index = 0; index < PlateCaptureJob.MAX_BLOCK_ENTITIES_PER_CHUNK + 8; index++) {
                BlockPos position = new BlockPos(index & 15, index >> 4, 3);
                entities.put(position, sign(position));
            }
            BlockPos visible = new BlockPos(1, 64, 3);
            entities.put(visible, sign(visible));
            when(fixture.chunk().getBlockEntities()).thenReturn(entities);
            MinecraftPlateCaptureSource.Options options = new MinecraftPlateCaptureSource.Options(fixture.worldId(), true, 64, 79, false);
            MinecraftPlateCaptureSource.CapturedChunk captured = new MinecraftPlateCaptureSource(fixture.runtime(), options)
                .capture(fixture.level(), 0, 0);
            assertEquals(1, captured.blockEntities().size());
            assertTrue(captured.blockEntities().containsKey(CellKeys.pack(1, 64, 3)));
            assertTrue(captured.blockEntitiesComplete());
        } finally {
            FidelitySettings.blockEntityTypes = types;
        }
    }

    @Test
    public void holdsFollowTheLeaseAndLoadedReadsTheChunkCache() {
        Fixture fixture = fixture();
        MinecraftPlateCaptureSource source = new MinecraftPlateCaptureSource(fixture.runtime(), MinecraftPlateCaptureSource.Options.column(fixture.worldId(), false));
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
        MinecraftPlateCaptureSource source = new MinecraftPlateCaptureSource(fixture.runtime(), MinecraftPlateCaptureSource.Options.column(fixture.worldId(), false));
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
        MinecraftProjectionService projections = mock(MinecraftProjectionService.class);
        WorldChangeTracker changes = new WorldChangeTracker();
        MinecraftPlateSnapshotCache snapshots = new MinecraftPlateSnapshotCache(changes, MinecraftPlateSnapshotCache.VIEW_LIMITS);
        when(runtime.projections()).thenReturn(projections);
        when(projections.plateSnapshots()).thenReturn(snapshots);
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
        return new Fixture(runtime, level, chunk, leases, lease, ready, worldId, changes);
    }

    private record Fixture(WormholesModRuntime runtime, ServerLevel level, LevelChunk chunk, ChunkLeaseRegistry<ServerLevel> leases,
                           ChunkLease lease, CompletableFuture<Boolean> ready, UUID worldId, WorldChangeTracker changes) {
    }

    private static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }
}
