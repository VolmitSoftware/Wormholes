package art.arcane.wormholes.render.client.session;

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

import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import art.arcane.wormholes.render.ProjectorSample;
import art.arcane.wormholes.render.client.ClientViewEntityTransform;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.render.plate.PlateCell;
import art.arcane.wormholes.render.plate.ViewPlate;
import art.arcane.wormholes.render.view.ProjectionContentView;
import art.arcane.wormholes.util.Direction;
import it.unimi.dsi.fastutil.longs.LongIterator;

class ClientViewPlateLightTest {
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
        ClientViewPlateLight<String> light = new ClientViewPlateLight<String>(plate, frame(), ClientViewPlateLightTest::glow, false);
        byte[] block = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        int x = ProjectionCellKey.unpackX(marker);
        int y = ProjectionCellKey.unpackY(marker);
        int z = ProjectionCellKey.unpackZ(marker);
        assertTrue(light.fill(x >> 4, y >> 4, z >> 4, block, sky));
        int index = ClientViewProtocol.brickCellIndex(x, y, z);
        assertEquals(15, BrickLightSource.nibble(block, index));
        assertEquals(13, BrickLightSource.nibble(sky, index), "bricks carry the raw destination sky light");
        int neighbour = ClientViewProtocol.brickCellIndex(x, y + 1, z);
        if (lit(plate, ProjectionCellKey.pack(x, y + 1, z))) {
            assertEquals(0, BrickLightSource.nibble(block, neighbour));
            assertEquals(13, BrickLightSource.nibble(sky, neighbour));
        }
        int dark = 0;
        PlateBox box = plate.box();
        for (int cy = y & ~15; cy < (y & ~15) + 16; cy++) {
            for (int cz = z & ~15; cz < (z & ~15) + 16; cz++) {
                for (int cx = x & ~15; cx < (x & ~15) + 16; cx++) {
                    if (box.index(cx, cy, cz) >= 0 && !lit(plate, ProjectionCellKey.pack(cx, cy, cz))) {
                        int cell = ClientViewProtocol.brickCellIndex(cx, cy, cz);
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
        PlateBox remote = ClientViewPlateLight.remoteBox(plate.box(), frame());
        assertTrue(remote.index(MARKER_X, MARKER_Y, MARKER_Z) >= 0);
        assertEquals(plate.box().cells(), remote.cells());
        AtomicInteger outside = new AtomicInteger();
        ClientViewPlateLight<String> light = new ClientViewPlateLight<String>(plate, frame(), (rx, ry, rz) -> {
            if (remote.index(rx, ry, rz) < 0) {
                outside.incrementAndGet();
            }
            return ProjectionContentView.packLight(15, 0);
        }, false);
        PlateBox box = plate.box();
        for (int sx = box.minX() >> 4; sx <= (box.minX() + box.sizeX() - 1) >> 4; sx++) {
            for (int sy = box.minY() >> 4; sy <= (box.minY() + box.sizeY() - 1) >> 4; sy++) {
                for (int sz = box.minZ() >> 4; sz <= (box.minZ() + box.sizeZ() - 1) >> 4; sz++) {
                    light.fill(sx, sy, sz, new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES], new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES]);
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
        int x = ProjectionCellKey.unpackX(marker);
        int y = ProjectionCellKey.unpackY(marker);
        int z = ProjectionCellKey.unpackZ(marker);
        byte[] block = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        ClientViewPlateLight<String> bright = new ClientViewPlateLight<String>(plate, frame(), (rx, ry, rz) -> ClientViewPlateLight.UNAVAILABLE,
            true);
        assertTrue(bright.fill(x >> 4, y >> 4, z >> 4, block, sky));
        assertEquals(15, BrickLightSource.nibble(block, ClientViewProtocol.brickCellIndex(x, y, z)));
        assertEquals(15, BrickLightSource.nibble(sky, ClientViewProtocol.brickCellIndex(x, y, z)));
        ClientViewPlateLight<String> blind = new ClientViewPlateLight<String>(plate, frame(), (rx, ry, rz) -> ClientViewPlateLight.UNAVAILABLE,
            false);
        assertFalse(blind.fill(x >> 4, y >> 4, z >> 4, new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES],
            new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES]));
    }

    @Test
    void cacheSharesOneSourcePerPlate() {
        SessionWorld world = new SessionWorld(24L);
        ViewPlate<String> plate = new SessionPortal("light", 0).build(world);
        ClientViewPlateLight.Cache<String> cache = new ClientViewPlateLight.Cache<String>();
        AtomicInteger built = new AtomicInteger();
        BrickLightSource first = cache.light(plate, () -> {
            built.incrementAndGet();
            return new ClientViewPlateLight<String>(plate, frame(), (rx, ry, rz) -> 0, false);
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
        ClientViewPlateLight.Cache<String> cache = new ClientViewPlateLight.Cache<String>();
        AtomicInteger samples = new AtomicInteger();
        ClientViewPlateLight.Sampler counting = (rx, ry, rz) -> {
            samples.incrementAndGet();
            return glow(rx, ry, rz);
        };
        long marker = find(first, MARKER);
        int sectionX = ProjectionCellKey.unpackX(marker) >> 4;
        int sectionY = ProjectionCellKey.unpackY(marker) >> 4;
        int sectionZ = ProjectionCellKey.unpackZ(marker) >> 4;
        BrickLightSource firstLight = cache.light(first, () -> new ClientViewPlateLight<String>(first, frame(), counting, false));
        byte[] block = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        assertTrue(firstLight.fill(sectionX, sectionY, sectionZ, block, sky));
        int sampled = samples.get();
        assertTrue(sampled > 0);
        ViewPlate<String> second = portal.build(world);
        BrickLightSource secondLight = cache.light(second, () -> new ClientViewPlateLight<String>(second, frame(), counting, false));
        byte[] reusedBlock = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        byte[] reusedSky = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        assertTrue(secondLight.fill(sectionX, sectionY, sectionZ, reusedBlock, reusedSky));
        assertEquals(sampled, samples.get(), "an unchanged section must not be sampled again");
        assertArrayEquals(block, reusedBlock);
        assertArrayEquals(sky, reusedSky);
        assertEquals(1L, ((ClientViewPlateLight<String>) secondLight).reusedSections());

        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        tracker.markChanged(world.worldId(), MARKER_X, MARKER_Y, MARKER_Z);
        assertTrue(second.refreshDirt(tracker));
        ViewPlate<String> third = portal.build(world);
        BrickLightSource thirdLight = cache.light(third, () -> new ClientViewPlateLight<String>(third, frame(), counting, false));
        assertTrue(thirdLight.fill(sectionX, sectionY, sectionZ, new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES],
            new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES]));
        assertTrue(samples.get() > sampled, "a section over a dirty destination chunk is sampled again");
    }

    @Test
    void unavailableSamplesKeepTheLastKnownDestinationLight() {
        SessionWorld world = new SessionWorld(26L);
        world.set(MARKER_X, MARKER_Y, MARKER_Z, MARKER);
        SessionPortal portal = new SessionPortal("light", 0);
        ViewPlate<String> first = portal.build(world);
        long marker = find(first, MARKER);
        int x = ProjectionCellKey.unpackX(marker);
        int y = ProjectionCellKey.unpackY(marker);
        int z = ProjectionCellKey.unpackZ(marker);
        ClientViewPlateLight.Cache<String> cache = new ClientViewPlateLight.Cache<String>();
        cache.light(first, () -> new ClientViewPlateLight<String>(first, frame(), ClientViewPlateLightTest::glow, false))
            .fill(x >> 4, y >> 4, z >> 4, new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES], new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES]);
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        tracker.markChanged(world.worldId(), MARKER_X, MARKER_Y, MARKER_Z);
        assertTrue(first.refreshDirt(tracker));
        ViewPlate<String> second = portal.build(world);
        byte[] block = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        assertTrue(cache.light(second, () -> new ClientViewPlateLight<String>(second, frame(), (rx, ry, rz) -> ClientViewPlateLight.UNAVAILABLE,
            false)).fill(x >> 4, y >> 4, z >> 4, block, sky));
        int index = ClientViewProtocol.brickCellIndex(x, y, z);
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
        int sectionX = ProjectionCellKey.unpackX(marker) >> 4;
        int sectionY = ProjectionCellKey.unpackY(marker) >> 4;
        int sectionZ = ProjectionCellKey.unpackZ(marker) >> 4;
        AtomicInteger available = new AtomicInteger();
        AtomicInteger missing = new AtomicInteger();
        new ClientViewPlateLight<String>(first, frame(), (rx, ry, rz) -> {
            (rx >= MARKER_X ? missing : available).incrementAndGet();
            return glow(rx, ry, rz);
        }, false).fill(sectionX, sectionY, sectionZ, new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES], new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES]);
        assertTrue(available.get() > 0 && missing.get() > 0, "the section should straddle a loaded and an unloaded destination chunk");
        ClientViewPlateLight.Cache<String> cache = new ClientViewPlateLight.Cache<String>();
        BrickLightSource partial = cache.light(first, () -> new ClientViewPlateLight<String>(first, frame(),
            (rx, ry, rz) -> rx >= MARKER_X ? ClientViewPlateLight.UNAVAILABLE : glow(rx, ry, rz), false));
        assertFalse(partial.fill(sectionX, sectionY, sectionZ, new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES],
            new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES]), "cells whose destination chunk is unloaded must not ship as black");

        ViewPlate<String> second = portal.build(world);
        byte[] block = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        assertTrue(cache.light(second, () -> new ClientViewPlateLight<String>(second, frame(), ClientViewPlateLightTest::glow, false))
            .fill(sectionX, sectionY, sectionZ, block, sky), "a later revision samples the destination again once it has loaded");
        int index = ClientViewProtocol.brickCellIndex(ProjectionCellKey.unpackX(marker), ProjectionCellKey.unpackY(marker),
            ProjectionCellKey.unpackZ(marker));
        assertEquals(15, BrickLightSource.nibble(block, index));
        assertEquals(13, BrickLightSource.nibble(sky, index));
    }

    @Test
    void cachedLightsReleaseTheirPlatesOnceNothingElseHoldsThem() throws InterruptedException {
        SessionWorld world = new SessionWorld(27L);
        world.set(MARKER_X, MARKER_Y, MARKER_Z, MARKER);
        SessionPortal portal = new SessionPortal("light", 0);
        ClientViewPlateLight.Cache<String> cache = new ClientViewPlateLight.Cache<String>();
        List<ViewPlate<String>> held = new ArrayList<ViewPlate<String>>();
        held.add(portal.build(world));
        WeakReference<ViewPlate<String>> first = new WeakReference<ViewPlate<String>>(held.get(0));
        lightEverySection(cache, held.get(0));
        ViewPlate<String> second = portal.build(world);
        long marker = find(second, MARKER);
        int sectionX = ProjectionCellKey.unpackX(marker) >> 4;
        int sectionY = ProjectionCellKey.unpackY(marker) >> 4;
        int sectionZ = ProjectionCellKey.unpackZ(marker) >> 4;
        AtomicInteger samples = new AtomicInteger();
        BrickLightSource secondLight = cache.light(second, () -> new ClientViewPlateLight<String>(second, frame(), (rx, ry, rz) -> {
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
        byte[] block = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        assertTrue(secondLight.fill(sectionX, sectionY, sectionZ, block, new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES]));
        assertEquals(0, samples.get(), "the live revision still reuses the section its collected predecessor computed");
        assertEquals(15, BrickLightSource.nibble(block, ClientViewProtocol.brickCellIndex(ProjectionCellKey.unpackX(marker),
            ProjectionCellKey.unpackY(marker), ProjectionCellKey.unpackZ(marker))));
    }

    private static void lightEverySection(ClientViewPlateLight.Cache<String> cache, ViewPlate<String> plate) {
        PlateBox box = plate.box();
        BrickLightSource light = cache.light(plate, () -> new ClientViewPlateLight<String>(plate, frame(), ClientViewPlateLightTest::glow, false));
        for (int sx = box.minX() >> 4; sx <= (box.minX() + box.sizeX() - 1) >> 4; sx++) {
            for (int sy = box.minY() >> 4; sy <= (box.minY() + box.sizeY() - 1) >> 4; sy++) {
                for (int sz = box.minZ() >> 4; sz <= (box.minZ() + box.sizeZ() - 1) >> 4; sz++) {
                    light.fill(sx, sy, sz, new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES], new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES]);
                }
            }
        }
    }

    private static int glow(int x, int y, int z) {
        return ProjectionContentView.packLight(13, x == MARKER_X && y == MARKER_Y && z == MARKER_Z ? 15 : 0);
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

    private static ClientViewEntityTransform.Frame frame() {
        return new ClientViewEntityTransform.Frame(11.4995D, 67.4995D, 20.5005D, PortalFrame.canonical(Direction.S), 200.4995D, 67.4995D,
            200.4995D, PortalFrame.canonical(Direction.N), false, 0, false, 24.0D);
    }
}
