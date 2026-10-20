package art.arcane.wormholes.modded.client.world;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.PreparedChunkColumns;
import art.arcane.wormholes.modded.mixin.client.ArrivalChunkAccess;
import art.arcane.wormholes.modded.mixin.client.ArrivalChunkCacheAccess;
import art.arcane.wormholes.modded.mixin.client.ArrivalChunkHeightAccess;
import art.arcane.wormholes.modded.mixin.client.ArrivalLevelAccess;
import art.arcane.wormholes.modded.mixin.client.ArrivalLevelTickersAccess;
import art.arcane.wormholes.modded.mixin.client.ArrivalLightEngineAccess;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class PreparedTerrainTest extends MinecraftTestBase {
    @Test
    public void terrainAndBlockEntitiesMoveIntoTheExistingLevel() {
        Scene scene = new Scene();
        PreparedTerrain.transfer(scene.staged, scene.target);
        assertSame(scene.stagedCache, scene.target.getChunkSource());
        assertSame(scene.target, scene.owner.get());
        assertSame(scene.target, scene.blockOwner.get());
        assertEquals(List.of(scene.ticker), scene.targetTickers);
        assertEquals(List.of(), scene.stagedTickers);
        verify(scene.target).unload(scene.oldChunk);
        verify(scene.target).onChunkLoaded(new ChunkPos(256, -129));
        verify((ArrivalLightEngineAccess) scene.light).wormholes$arrivalHeight(scene.target);
    }

    @Test
    public void pendingLightCannotBeAdoptedAsReadyTerrain() {
        Scene scene = new Scene();
        scene.pending.add(() -> {});
        assertThrows(IllegalStateException.class, () -> PreparedTerrain.transfer(scene.staged, scene.target));
        assertSame(scene.originalCache, scene.target.getChunkSource());
        assertSame(scene.staged, scene.owner.get());
    }

    @Test
    public void overlappingArrivalKeepsAuthoritativeColumnsAndTheirLight() {
        Scene scene = new Scene();
        AtomicReferenceArray<LevelChunk> columns = new AtomicReferenceArray<>(new LevelChunk[]{scene.chunk, null});
        when(((PreparedChunkColumns) scene.stagedCache).wormholes$columns()).thenReturn(columns);
        when(((PreparedChunkColumns) scene.stagedCache).wormholes$retainColumn(scene.oldChunk)).thenAnswer(invocation -> {
            columns.set(1, scene.oldChunk);
            return true;
        });
        ChunkPos position = new ChunkPos(259, -129);
        when(scene.oldChunk.getPos()).thenReturn(position);
        when(scene.oldChunk.getSections()).thenReturn(new LevelChunkSection[]{mock(LevelChunkSection.class)});
        when(scene.oldChunk.getSectionYFromSectionIndex(0)).thenReturn(4);
        LevelLightEngine previousLight = mock(LevelLightEngine.class);
        LayerLightEventListener blockLight = mock(LayerLightEventListener.class);
        LayerLightEventListener skyLight = mock(LayerLightEventListener.class);
        when(scene.originalCache.getLightEngine()).thenReturn(previousLight);
        when(previousLight.getMinLightSection()).thenReturn(4);
        when(previousLight.getMaxLightSection()).thenReturn(5);
        when(previousLight.getLayerListener(LightLayer.BLOCK)).thenReturn(blockLight);
        when(previousLight.getLayerListener(LightLayer.SKY)).thenReturn(skyLight);
        SectionPos section = SectionPos.of(position, 4);
        DataLayer block = new DataLayer(7);
        DataLayer sky = new DataLayer(15);
        when(blockLight.getDataLayerData(section)).thenReturn(block);
        when(skyLight.getDataLayerData(section)).thenReturn(sky);
        PreparedTerrain.transfer(scene.staged, scene.target);
        assertSame(scene.oldChunk, ((PreparedChunkColumns) scene.target.getChunkSource()).wormholes$columns().get(1));
        verify(scene.target, never()).unload(scene.oldChunk);
        verify(scene.target).onChunkLoaded(position);
        verify(scene.light).queueSectionData(eq(LightLayer.BLOCK), eq(section),
            argThat(data -> data != block && data.get(0, 0, 0) == 7));
        verify(scene.light).queueSectionData(eq(LightLayer.SKY), eq(section),
            argThat(data -> data != sky && data.get(0, 0, 0) == 15));
        verify(scene.light).setLightEnabled(position, true);
        verify(scene.light).runLightUpdates();
    }

    @Test
    public void anotherDimensionCannotReplaceTheActiveTerrain() {
        Scene scene = new Scene();
        when(scene.staged.dimension()).thenReturn(Level.END);
        assertThrows(IllegalArgumentException.class, () -> PreparedTerrain.transfer(scene.staged, scene.target));
        assertSame(scene.originalCache, scene.target.getChunkSource());
        verify(scene.target, never()).unload(scene.oldChunk);
    }

    private static final class Scene {
        private final ClientLevel staged = level();
        private final ClientLevel target = level();
        private final ClientChunkCache originalCache = cache();
        private final ClientChunkCache stagedCache = cache();
        private final AtomicReference<ClientChunkCache> current = new AtomicReference<>(originalCache);
        private final LevelChunk oldChunk = chunk();
        private final LevelChunk chunk = chunk();
        private final LevelChunk empty = chunk();
        private final BlockEntity block = mock(BlockEntity.class);
        private final AtomicReference<Level> owner = new AtomicReference<>(staged);
        private final AtomicReference<Level> blockOwner = new AtomicReference<>(staged);
        private final LevelLightEngine light = mock(LevelLightEngine.class, withSettings().extraInterfaces(ArrivalLightEngineAccess.class));
        private final TickingBlockEntity ticker = mock(TickingBlockEntity.class);
        private final List<TickingBlockEntity> stagedTickers = new ArrayList<>(List.of(ticker));
        private final List<TickingBlockEntity> targetTickers = new ArrayList<>();
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();

        private Scene() {
            when(staged.dimension()).thenReturn(Level.OVERWORLD);
            when(target.dimension()).thenReturn(Level.OVERWORLD);
            when(staged.getChunkSource()).thenReturn(stagedCache);
            when(target.getChunkSource()).thenAnswer(invocation -> current.get());
            doAnswer(invocation -> {
                current.set(invocation.getArgument(0));
                return null;
            }).when((ArrivalLevelAccess) target).wormholes$arrivalChunks(any());
            when(((ArrivalLevelAccess) staged).wormholes$pendingLight()).thenReturn(pending);
            when(((PreparedChunkColumns) originalCache).wormholes$columns()).thenReturn(new AtomicReferenceArray<>(new LevelChunk[]{oldChunk}));
            when(((PreparedChunkColumns) stagedCache).wormholes$columns()).thenReturn(new AtomicReferenceArray<>(new LevelChunk[]{chunk}));
            when(((ArrivalChunkCacheAccess) stagedCache).wormholes$emptyChunk()).thenReturn(empty);
            when(stagedCache.getLightEngine()).thenReturn(light);
            when(chunk.getBlockEntities()).thenReturn(Map.of(new BlockPos(4104, 80, -2055), block));
            when(chunk.getPos()).thenReturn(new ChunkPos(256, -129));
            doAnswer(invocation -> {
                owner.set(invocation.getArgument(0));
                return null;
            }).when((ArrivalChunkAccess) chunk).wormholes$arrivalLevel(any());
            doAnswer(invocation -> {
                blockOwner.set(invocation.getArgument(0));
                return null;
            }).when(block).setLevel(any());
            when(((ArrivalLevelTickersAccess) staged).wormholes$blockEntityTickers()).thenReturn(stagedTickers);
            when(((ArrivalLevelTickersAccess) target).wormholes$blockEntityTickers()).thenReturn(targetTickers);
            when(((ArrivalLevelTickersAccess) staged).wormholes$pendingBlockEntityTickers()).thenReturn(new ArrayList<>());
            when(((ArrivalLevelTickersAccess) target).wormholes$pendingBlockEntityTickers()).thenReturn(new ArrayList<>());
        }

        private static ClientLevel level() {
            return mock(ClientLevel.class, withSettings().extraInterfaces(ArrivalLevelAccess.class, ArrivalLevelTickersAccess.class));
        }

        private static ClientChunkCache cache() {
            return mock(ClientChunkCache.class, withSettings().extraInterfaces(PreparedChunkColumns.class, ArrivalChunkCacheAccess.class));
        }

        private static LevelChunk chunk() {
            return mock(LevelChunk.class, withSettings().extraInterfaces(ArrivalChunkAccess.class, ArrivalChunkHeightAccess.class));
        }
    }
}
