package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import art.arcane.optics.client.LightSampler;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.optics.scan.ProjectorSample;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.plate.PlateCell;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import it.unimi.dsi.fastutil.longs.LongIterator;
import art.arcane.optics.client.PlateLight;

class ViewStreamPlateLightTest {
    private static final String MARKER = "minecraft:glowstone";
    private static final int MARKER_X = 200;
    private static final int MARKER_Y = 70;
    private static final int MARKER_Z = 195;

    @Test
    void destinationLightLandsOnTheLocalCellThatShowsTheSource() {
        SessionWorld world = new SessionWorld(21L);
        world.set(MARKER_X, MARKER_Y, MARKER_Z, MARKER);
        ViewPlate<String> plate = new SessionPortal("light", 0).build(world);
        long marker = find(plate, MARKER);
        PlateLight<String> light = new PlateLight<String>(plate, frame(), ViewStreamPlateLightTest::glow, false);
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        int x = CellKeys.unpackX(marker);
        int y = CellKeys.unpackY(marker);
        int z = CellKeys.unpackZ(marker);
        assertTrue(light.fill(x >> 4, y >> 4, z >> 4, block, sky));
        int index = ViewStreamLimits.brickCellIndex(x, y, z);
        assertEquals(15, BrickLightSource.nibble(block, index));
        assertEquals(13, BrickLightSource.nibble(sky, index), "bricks carry the raw destination sky light");
        int neighbour = ViewStreamLimits.brickCellIndex(x, y + 1, z);
        if (lit(plate, CellKeys.pack(x, y + 1, z))) {
            assertEquals(0, BrickLightSource.nibble(block, neighbour));
            assertEquals(13, BrickLightSource.nibble(sky, neighbour));
        }
        int dark = 0;
        BlockBox box = plate.box();
        for (int cy = y & ~15; cy < (y & ~15) + 16; cy++) {
            for (int cz = z & ~15; cz < (z & ~15) + 16; cz++) {
                for (int cx = x & ~15; cx < (x & ~15) + 16; cx++) {
                    if (box.index(cx, cy, cz) >= 0 && !lit(plate, CellKeys.pack(cx, cy, cz))) {
                        int cell = ViewStreamLimits.brickCellIndex(cx, cy, cz);
                        assertEquals(0, BrickLightSource.nibble(sky, cell));
                        dark++;
                    }
                }
            }
        }
        assertTrue(dark > 0, "the section should hold buried or backing cells that stay dark");
    }

    @Test
    void remoteBoxCoversTheSampledFootprint() {
        SessionWorld world = new SessionWorld(22L);
        world.set(MARKER_X, MARKER_Y, MARKER_Z, MARKER);
        ViewPlate<String> plate = new SessionPortal("light", 0).build(world);
        BlockBox remote = PlateLight.remoteBox(plate.box(), frame());
        assertTrue(remote.index(MARKER_X, MARKER_Y, MARKER_Z) >= 0);
        assertEquals(plate.box().cells(), remote.cells());
        AtomicInteger outside = new AtomicInteger();
        PlateLight<String> light = new PlateLight<String>(plate, frame(), (rx, ry, rz) -> {
            if (remote.index(rx, ry, rz) < 0) {
                outside.incrementAndGet();
            }
            return ContentView.packLight(15, 0);
        }, false);
        BlockBox box = plate.box();
        for (int sx = box.minX() >> 4; sx <= (box.minX() + box.sizeX() - 1) >> 4; sx++) {
            for (int sy = box.minY() >> 4; sy <= (box.minY() + box.sizeY() - 1) >> 4; sy++) {
                for (int sz = box.minZ() >> 4; sz <= (box.minZ() + box.sizeZ() - 1) >> 4; sz++) {
                    light.fill(sx, sy, sz, new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES], new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES]);
                }
            }
        }
        assertEquals(0, outside.get());
    }

    @Test
    void fullBrightAndUnavailableSamples() {
        SessionWorld world = new SessionWorld(23L);
        world.set(MARKER_X, MARKER_Y, MARKER_Z, MARKER);
        ViewPlate<String> plate = new SessionPortal("light", 0).build(world);
        long marker = find(plate, MARKER);
        int x = CellKeys.unpackX(marker);
        int y = CellKeys.unpackY(marker);
        int z = CellKeys.unpackZ(marker);
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        PlateLight<String> bright = new PlateLight<String>(plate, frame(), (rx, ry, rz) -> PlateLight.UNAVAILABLE,
            true);
        assertTrue(bright.fill(x >> 4, y >> 4, z >> 4, block, sky));
        assertEquals(15, BrickLightSource.nibble(block, ViewStreamLimits.brickCellIndex(x, y, z)));
        assertEquals(15, BrickLightSource.nibble(sky, ViewStreamLimits.brickCellIndex(x, y, z)));
        PlateLight<String> blind = new PlateLight<String>(plate, frame(), (rx, ry, rz) -> PlateLight.UNAVAILABLE,
            false);
        assertFalse(blind.fill(x >> 4, y >> 4, z >> 4, new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES],
            new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES]));
    }

    @Test
    void cacheSharesOneSourcePerPlate() {
        SessionWorld world = new SessionWorld(24L);
        ViewPlate<String> plate = new SessionPortal("light", 0).build(world);
        PlateLight.Cache<String> cache = new PlateLight.Cache<String>();
        AtomicInteger built = new AtomicInteger();
        BrickLightSource first = cache.light(plate, () -> {
            built.incrementAndGet();
            return new PlateLight<String>(plate, frame(), (rx, ry, rz) -> 0, false);
        });
        BrickLightSource second = cache.light(plate, () -> {
            built.incrementAndGet();
            return BrickLightSource.NONE;
        });
        assertSame(first, second);
        assertEquals(1, built.get());
        assertNotEquals(BrickLightSource.NONE, first);
        assertSame(BrickLightSource.NONE, cache.light(new SessionPortal("other", 8).build(world), () -> null));
    }

    @Test
    void laterRevisionsReuseLightAwayFromDirtyChunks() {
        SessionWorld world = new SessionWorld(25L);
        world.set(MARKER_X, MARKER_Y, MARKER_Z, MARKER);
        SessionPortal portal = new SessionPortal("light", 0);
        ViewPlate<String> first = portal.build(world);
        PlateLight.Cache<String> cache = new PlateLight.Cache<String>();
        AtomicInteger samples = new AtomicInteger();
        LightSampler counting = (rx, ry, rz) -> {
            samples.incrementAndGet();
            return glow(rx, ry, rz);
        };
        long marker = find(first, MARKER);
        int sectionX = CellKeys.unpackX(marker) >> 4;
        int sectionY = CellKeys.unpackY(marker) >> 4;
        int sectionZ = CellKeys.unpackZ(marker) >> 4;
        BrickLightSource firstLight = cache.light(first, () -> new PlateLight<String>(first, frame(), counting, false));
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        assertTrue(firstLight.fill(sectionX, sectionY, sectionZ, block, sky));
        int sampled = samples.get();
        assertTrue(sampled > 0);
        ViewPlate<String> second = portal.build(world);
        BrickLightSource secondLight = cache.light(second, () -> new PlateLight<String>(second, frame(), counting, false));
        byte[] reusedBlock = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] reusedSky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        assertTrue(secondLight.fill(sectionX, sectionY, sectionZ, reusedBlock, reusedSky));
        assertEquals(sampled, samples.get(), "an unchanged section must not be sampled again");
        assertArrayEquals(block, reusedBlock);
        assertArrayEquals(sky, reusedSky);
        assertEquals(1L, ((PlateLight<String>) secondLight).reusedSections());

        WorldChangeTracker tracker = new WorldChangeTracker();
        tracker.markChanged(world.worldId(), MARKER_X, MARKER_Y, MARKER_Z);
        assertTrue(second.refreshDirt(tracker));
        ViewPlate<String> third = portal.build(world);
        BrickLightSource thirdLight = cache.light(third, () -> new PlateLight<String>(third, frame(), counting, false));
        assertTrue(thirdLight.fill(sectionX, sectionY, sectionZ, new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES],
            new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES]));
        assertTrue(samples.get() > sampled, "a section over a dirty destination chunk is sampled again");
    }

    @Test
    void unavailableSamplesKeepTheLastKnownDestinationLight() {
        SessionWorld world = new SessionWorld(26L);
        world.set(MARKER_X, MARKER_Y, MARKER_Z, MARKER);
        SessionPortal portal = new SessionPortal("light", 0);
        ViewPlate<String> first = portal.build(world);
        long marker = find(first, MARKER);
        int x = CellKeys.unpackX(marker);
        int y = CellKeys.unpackY(marker);
        int z = CellKeys.unpackZ(marker);
        PlateLight.Cache<String> cache = new PlateLight.Cache<String>();
        cache.light(first, () -> new PlateLight<String>(first, frame(), ViewStreamPlateLightTest::glow, false))
            .fill(x >> 4, y >> 4, z >> 4, new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES], new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES]);
        WorldChangeTracker tracker = new WorldChangeTracker();
        tracker.markChanged(world.worldId(), MARKER_X, MARKER_Y, MARKER_Z);
        assertTrue(first.refreshDirt(tracker));
        ViewPlate<String> second = portal.build(world);
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        assertTrue(cache.light(second, () -> new PlateLight<String>(second, frame(), (rx, ry, rz) -> PlateLight.UNAVAILABLE,
            false)).fill(x >> 4, y >> 4, z >> 4, block, sky));
        int index = ViewStreamLimits.brickCellIndex(x, y, z);
        assertEquals(15, BrickLightSource.nibble(block, index), "an unloaded destination chunk keeps the last light instead of going dark");
        assertEquals(13, BrickLightSource.nibble(sky, index));
    }

    @Test
    void partlyUnloadedDestinationsShipNoLightInsteadOfBlackCells() {
        SessionWorld world = new SessionWorld(28L);
        world.set(MARKER_X, MARKER_Y, MARKER_Z, MARKER);
        SessionPortal portal = new SessionPortal("light", 0);
        ViewPlate<String> first = portal.build(world);
        long marker = find(first, MARKER);
        int sectionX = CellKeys.unpackX(marker) >> 4;
        int sectionY = CellKeys.unpackY(marker) >> 4;
        int sectionZ = CellKeys.unpackZ(marker) >> 4;
        AtomicInteger available = new AtomicInteger();
        AtomicInteger missing = new AtomicInteger();
        new PlateLight<String>(first, frame(), (rx, ry, rz) -> {
            (rx >= MARKER_X ? missing : available).incrementAndGet();
            return glow(rx, ry, rz);
        }, false).fill(sectionX, sectionY, sectionZ, new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES], new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES]);
        assertTrue(available.get() > 0 && missing.get() > 0, "the section should straddle a loaded and an unloaded destination chunk");
        PlateLight.Cache<String> cache = new PlateLight.Cache<String>();
        BrickLightSource partial = cache.light(first, () -> new PlateLight<String>(first, frame(),
            (rx, ry, rz) -> rx >= MARKER_X ? PlateLight.UNAVAILABLE : glow(rx, ry, rz), false));
        assertFalse(partial.fill(sectionX, sectionY, sectionZ, new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES],
            new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES]), "cells whose destination chunk is unloaded must not ship as black");

        ViewPlate<String> second = portal.build(world);
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        assertTrue(cache.light(second, () -> new PlateLight<String>(second, frame(), ViewStreamPlateLightTest::glow, false))
            .fill(sectionX, sectionY, sectionZ, block, sky), "a later revision samples the destination again once it has loaded");
        int index = ViewStreamLimits.brickCellIndex(CellKeys.unpackX(marker), CellKeys.unpackY(marker),
            CellKeys.unpackZ(marker));
        assertEquals(15, BrickLightSource.nibble(block, index));
        assertEquals(13, BrickLightSource.nibble(sky, index));
    }

    @Test
    void cachedLightsReleaseTheirPlatesOnceNothingElseHoldsThem() throws InterruptedException {
        SessionWorld world = new SessionWorld(27L);
        world.set(MARKER_X, MARKER_Y, MARKER_Z, MARKER);
        SessionPortal portal = new SessionPortal("light", 0);
        PlateLight.Cache<String> cache = new PlateLight.Cache<String>();
        List<ViewPlate<String>> held = new ArrayList<ViewPlate<String>>();
        held.add(portal.build(world));
        WeakReference<ViewPlate<String>> first = new WeakReference<ViewPlate<String>>(held.get(0));
        lightEverySection(cache, held.get(0));
        ViewPlate<String> second = portal.build(world);
        long marker = find(second, MARKER);
        int sectionX = CellKeys.unpackX(marker) >> 4;
        int sectionY = CellKeys.unpackY(marker) >> 4;
        int sectionZ = CellKeys.unpackZ(marker) >> 4;
        AtomicInteger samples = new AtomicInteger();
        BrickLightSource secondLight = cache.light(second, () -> new PlateLight<String>(second, frame(), (rx, ry, rz) -> {
            samples.incrementAndGet();
            return glow(rx, ry, rz);
        }, false));
        held.add(new SessionPortal("other", 8).build(world));
        WeakReference<ViewPlate<String>> other = new WeakReference<ViewPlate<String>>(held.get(1));
        lightEverySection(cache, held.get(1));
        held.clear();

        for (int attempt = 0; attempt < 100 && (first.get() != null || other.get() != null || cache.size() != 1); attempt++) {
            System.gc();
            Thread.sleep(10L);
        }

        assertNull(first.get(), "a replaced revision must not stay reachable through its cached light");
        assertNull(other.get(), "a plate nothing holds must not stay reachable through its cached light");
        assertEquals(1, cache.size());
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        assertTrue(secondLight.fill(sectionX, sectionY, sectionZ, block, new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES]));
        assertEquals(0, samples.get(), "the live revision still reuses the section its collected predecessor computed");
        assertEquals(15, BrickLightSource.nibble(block, ViewStreamLimits.brickCellIndex(CellKeys.unpackX(marker),
            CellKeys.unpackY(marker), CellKeys.unpackZ(marker))));
    }

    private static void lightEverySection(PlateLight.Cache<String> cache, ViewPlate<String> plate) {
        BlockBox box = plate.box();
        BrickLightSource light = cache.light(plate, () -> new PlateLight<String>(plate, frame(), ViewStreamPlateLightTest::glow, false));
        for (int sx = box.minX() >> 4; sx <= (box.minX() + box.sizeX() - 1) >> 4; sx++) {
            for (int sy = box.minY() >> 4; sy <= (box.minY() + box.sizeY() - 1) >> 4; sy++) {
                for (int sz = box.minZ() >> 4; sz <= (box.minZ() + box.sizeZ() - 1) >> 4; sz++) {
                    light.fill(sx, sy, sz, new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES], new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES]);
                }
            }
        }
    }

    private static int glow(int x, int y, int z) {
        return ContentView.packLight(13, x == MARKER_X && y == MARKER_Y && z == MARKER_Z ? 15 : 0);
    }

    private static boolean lit(ViewPlate<String> plate, long key) {
        PlateCell<String> cell = plate.cell(key);
        if (cell == null) {
            return false;
        }
        ProjectorSample.Kind kind = cell.kind();
        return kind == ProjectorSample.Kind.BLOCK || kind == ProjectorSample.Kind.REMOTE_AIR || kind == ProjectorSample.Kind.MASK_AIR;
    }

    private static long find(ViewPlate<String> plate, String state) {
        LongIterator keys = plate.cellKeys().iterator();
        while (keys.hasNext()) {
            long key = keys.nextLong();
            PlateCell<String> cell = plate.cell(key);
            if (cell != null && state.equals(cell.sourceData()) && cell.kind() == ProjectorSample.Kind.BLOCK) {
                return key;
            }
        }
        throw new AssertionError(state + " is not part of the plate");
    }

    private static ViewWindow frame() {
        return ViewWindow.between(new Vec3d(11.4995D, 67.4995D, 20.5005D), Frame.canonical(Face.S), new Vec3d(200.4995D, 67.4995D, 200.4995D), Frame.canonical(Face.N), false, 24.0D);
    }
}
