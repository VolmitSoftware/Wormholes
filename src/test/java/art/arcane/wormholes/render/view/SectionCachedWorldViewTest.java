package art.arcane.wormholes.render.view;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.state.StateProperties;
import art.arcane.optics.view.BlockStates;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import art.arcane.optics.view.CachedSection;
import art.arcane.optics.view.SectionCache;
import art.arcane.optics.view.SectionSource;

class SectionCachedWorldViewTest {
    private static final SectionCache.Limits LIMITS = new SectionCache.Limits(true, 1L << 30, 16, 600);

    @Test
    void cachedSectionsServeWithoutTouchingTheLiveWorld() {
        Fixture fixture = new Fixture(LIMITS);
        fixture.source.fill = fixture.stone;

        assertSame(fixture.stone, fixture.view.sampleBlockData(3, 70, -5));
        assertEquals(Material.STONE, fixture.view.material(3, 70, -5));
        assertEquals(2, fixture.view.buriedDepth(8, 72, -8));
        verify(fixture.live, never()).sampleBlockData(anyInt(), anyInt(), anyInt());
        verify(fixture.live, never()).material(anyInt(), anyInt(), anyInt());
    }

    @Test
    void unfilledSectionsFallBackToLiveReadsOnLoadedChunks() {
        Fixture fixture = new Fixture(LIMITS);
        fixture.source.fill = null;
        BlockData liveData = mock(BlockData.class);
        when(fixture.live.sampleBlockData(3, 70, -5)).thenReturn(liveData);
        when(fixture.live.material(3, 70, -5)).thenReturn(Material.DIRT);

        assertSame(liveData, fixture.view.sampleBlockData(3, 70, -5));
        assertEquals(Material.DIRT, fixture.view.material(3, 70, -5));
        assertEquals(-1, fixture.view.buriedDepth(3, 70, -5));
        assertTrue(fixture.view.isChunkReady(3, -5));
        assertTrue(fixture.requests.isEmpty());
    }

    @Test
    void unloadedChunksReturnNoSampleAndRequestAnAsyncLoad() {
        Fixture fixture = new Fixture(LIMITS);
        fixture.source.fill = fixture.stone;
        when(fixture.world.isChunkLoaded(0, -1)).thenReturn(false);

        assertNull(fixture.view.sampleBlockData(3, 70, -5));
        assertFalse(fixture.view.isChunkReady(3, -5));
        assertEquals(-1, fixture.view.getLight(3, 70, -5));
        fixture.view.requestChunk(3, -5);

        assertEquals(List.of("0,-1", "0,-1", "0,-1"), fixture.requests);
        verify(fixture.live, never()).sampleBlockData(anyInt(), anyInt(), anyInt());
        verify(fixture.live, never()).getLight(anyInt(), anyInt(), anyInt());
    }

    @Test
    void anArrivedChunkCapturesTheSectionsItsReadersWanted() {
        Fixture fixture = new Fixture(new SectionCache.Limits(true, 1L << 30, 0, 600));
        fixture.source.fill = fixture.stone;
        when(fixture.world.isChunkLoaded(0, -1)).thenReturn(false);
        assertNull(fixture.view.sampleBlockData(3, 70, -5));
        assertNull(fixture.view.material(3, 90, -5));
        assertFalse(fixture.view.isChunkReady(3, -5));

        when(fixture.world.isChunkLoaded(0, -1)).thenReturn(true);
        fixture.view.chunkArrived(0, -1);
        when(fixture.world.isChunkLoaded(0, -1)).thenReturn(false);

        assertTrue(fixture.view.isChunkReady(3, -5));
        assertSame(fixture.stone, fixture.view.sampleBlockData(3, 70, -5));
        assertEquals(Material.STONE, fixture.view.material(3, 90, -5));
        assertNull(fixture.view.sampleBlockData(3, 20, -5));
        verify(fixture.live, never()).sampleBlockData(anyInt(), anyInt(), anyInt());
    }

    @Test
    void cachedColumnsStayReadyAfterTheirChunkUnloads() {
        Fixture fixture = new Fixture(LIMITS);
        fixture.source.fill = fixture.stone;
        assertSame(fixture.stone, fixture.view.sampleBlockData(3, 70, -5));

        when(fixture.world.isChunkLoaded(0, -1)).thenReturn(false);

        assertTrue(fixture.view.isChunkReady(3, -5));
        assertSame(fixture.stone, fixture.view.sampleBlockData(3, 70, -5));
    }

    @Test
    void otherThreadsAndDisabledCachesReadTheLiveWorld() throws InterruptedException {
        Fixture fixture = new Fixture(LIMITS);
        fixture.source.fill = fixture.stone;
        BlockData liveData = mock(BlockData.class);
        when(fixture.live.sampleBlockData(3, 70, -5)).thenReturn(liveData);
        when(fixture.world.isChunkLoaded(0, -1)).thenReturn(false);
        AtomicReference<BlockData> offThread = new AtomicReference<BlockData>();
        AtomicReference<Boolean> offThreadReady = new AtomicReference<Boolean>();

        Thread reader = new Thread(() -> {
            offThread.set(fixture.view.sampleBlockData(3, 70, -5));
            offThreadReady.set(Boolean.valueOf(fixture.view.isChunkReady(3, -5)));
        });
        reader.start();
        reader.join();

        assertSame(liveData, offThread.get());
        assertTrue(offThreadReady.get().booleanValue());

        fixture.cache.configure(new SectionCache.Limits(false, 1L << 30, 16, 600));
        assertSame(liveData, fixture.view.sampleBlockData(3, 70, -5));
        assertTrue(fixture.view.isChunkReady(3, -5));
        assertEquals(-1, fixture.view.buriedDepth(8, 72, -8));
    }

    private static final class Fixture {
        private final World world;
        private final ProjectionWorldView live;
        private final BlockData stone;
        private final UniformSource source;
        private final SectionCache<BlockData, Material> cache;
        private final List<String> requests;
        private final SectionCachedWorldView view;

        private Fixture(SectionCache.Limits limits) {
            this.world = mock(World.class);
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            this.live = mock(ProjectionWorldView.class);
            this.stone = mock(BlockData.class);
            this.source = new UniformSource(world);
            this.cache = new SectionCache<BlockData, Material>(new StoneBlocks(), limits);
            this.requests = new ArrayList<String>();
            SectionCache<BlockData, Material>.WorldSections sections = cache.world(source, -4, 19);
            this.view = new SectionCachedWorldView(world, live, sections,
                (requested, chunkX, chunkZ) -> requests.add(chunkX + "," + chunkZ), Thread.currentThread());
            cache.tick(1);
        }
    }

    private static final class UniformSource implements SectionSource<BlockData, Material> {
        private final World world;
        private BlockData fill;

        private UniformSource(World world) {
            this.world = world;
        }

        @Override
        public boolean columnAvailable(int chunkX, int chunkZ) {
            return fill != null && world.isChunkLoaded(chunkX, chunkZ);
        }

        @Override
        public boolean capture(int sectionX, int sectionY, int sectionZ, CachedSection.Builder<BlockData, Material> builder) {
            if (!columnAvailable(sectionX, sectionZ)) {
                return false;
            }
            for (int index = 0; index < CachedSection.CELLS; index++) {
                builder.set(index, fill, Material.STONE);
            }
            return true;
        }

        @Override
        public void discardColumn(int chunkX, int chunkZ) {
        }

        @Override
        public void endTick() {
        }
    }

    private static final class StoneBlocks implements BlockStates<BlockData, Material> {
        @Override
        public BlockData air() {
            return null;
        }

        @Override
        public BlockData occluded() {
            return null;
        }

        @Override
        public boolean isOccluded(BlockData block) {
            return false;
        }

        @Override
        public Material material(BlockData block) {
            return block.getMaterial();
        }

        @Override
        public String materialName(Material material) {
            return material.name();
        }

        @Override
        public boolean blockEntityCandidate(Material material) {
            return false;
        }

        @Override
        public boolean isAir(Material material) {
            return ProjectionWorldView.isAir(material);
        }

        @Override
        public boolean isOccluding(Material material) {
            return material == Material.STONE;
        }

        @Override
        public boolean occludes(BlockData block) {
            return block != null && isOccluding(material(block));
        }

        @Override
        public boolean requiresTransform(BlockData block) {
            return false;
        }

        @Override
        public BlockData transform(BlockData block, AxisPermutation permutation) {
            return block;
        }

        @Override
        public StateProperties properties(BlockData block) {
            return StateProperties.EMPTY;
        }

        @Override
        public BlockData withProperties(BlockData block, StateProperties properties) {
            return block;
        }
    }
}
