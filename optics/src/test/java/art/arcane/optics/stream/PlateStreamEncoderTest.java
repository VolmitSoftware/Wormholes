package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.scan.ProjectorSample;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.volume.LodPolicy;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.plate.PlateCell;
import art.arcane.optics.plate.PlateGrid;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.plate.ViewPlateBuilder;
import art.arcane.optics.plate.ViewPlateKey;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

final class PlateStreamEncoderTest {
    static int[] remoteOf(int localX, int localY, int localZ) {
        OpticTransform transform = OpticTransform.between(Frame.canonical(Face.S).view(false), 11.4995D, 67.4995D, 20.5005D,
            Frame.canonical(Face.N).view(false), 200.4995D, 67.4995D, 200.4995D);
        double[] out = new double[3];
        transform.snappedPointInto(localX + 0.5D, localY + 0.5D, localZ + 0.5D, out);
        return new int[] {(int) Math.floor(out[0]), (int) Math.floor(out[1]), (int) Math.floor(out[2])};
    }

    static long firstCellOfKind(ViewPlate<String> plate, ProjectorSample.Kind kind) {
        for (long key : plate.cellKeys()) {
            if (plate.cell(key).kind() == kind) {
                return key;
            }
        }
        throw new IllegalStateException("no " + kind + " cell in the plate");
    }

    static ViewPlate<String> smallPlate(SyntheticWorld world, boolean blockEntities) {
        ApertureCells geometry = new ApertureCells();
        geometry.setArea(new Box(10, 12.999, 66, 68.999, 20, 20.999));
        ViewPlateKey key = new ViewPlateKey(UUID.nameUUIDFromBytes("small".getBytes()), world, false, 0, 0L);
        ViewPlateBuilder.Request<String, String, ContentView<String, String>> request =
            new ViewPlateBuilder.Request<String, String, ContentView<String, String>>(key, geometry, world,
                Frame.canonical(Face.S), Frame.canonical(Face.N), 11.4995, 67.4995, 20.5005,
                200.4995, 67.4995, 200.4995, false, 0, 24.0D, 8.0D, 0.75D, true, SyntheticWorld.AIR, LodPolicy.NONE, blockEntities,
                0L, 0L, 0L, SyntheticBlocks.INSTANCE);
        return ViewPlateBuilder.build(request);
    }

    @Test
    void sectionCaptureClipsBeforeAllocatingTheRenderDistanceVolume() {
        SyntheticWorld world = new SyntheticWorld(41L);
        ApertureCells geometry = new ApertureCells();
        geometry.setArea(new Box(10, 12.999, 66, 68.999, 20, 20.999));
        ViewPlateKey key = new ViewPlateKey(UUID.randomUUID(), world, false, 0, 0L);
        ViewPlateBuilder.Request<String, String, ContentView<String, String>> request =
            new ViewPlateBuilder.Request<String, String, ContentView<String, String>>(key, geometry, world,
                Frame.canonical(Face.S), Frame.canonical(Face.N), 11.4995, 67.4995, 20.5005,
                200.4995, 67.4995, 200.4995, false, 0, 512, 512, 0, false, SyntheticWorld.AIR, LodPolicy.NONE, false,
                0L, 0L, 0L, SyntheticBlocks.INSTANCE);
        BlockBox clip = new BlockBox(0, 64, 32, 16, 16, 16);
        ViewPlateBuilder.Job<String, Object> job = ViewPlateBuilder.sectionJob(request, clip);
        assertTrue(job.predictedBytes() < 32 * 1024);
        while (!job.step(128)) {
        }
        assertEquals(clip, job.result().box());
        ViewPlateBuilder.Footprint footprint = ViewPlateBuilder.sectionFootprint(request, clip);
        assertTrue(footprint.chunkCount() <= 9);
        BlockBox metadata = ViewPlateBuilder.sectionDestinationBox(request, clip);
        assertEquals(32, metadata.sizeX());
        assertEquals(32, metadata.sizeY());
        assertEquals(32, metadata.sizeZ());
        assertEquals(footprint.minChunkX(), job.result().minChunkX());
        assertEquals(footprint.minChunkZ(), job.result().minChunkZ());
        assertEquals(footprint.maxChunkX(), job.result().maxChunkX());
        assertEquals(footprint.maxChunkZ(), job.result().maxChunkZ());
        assertEquals(1, new PlateStreamEncoder<String>(new SessionPalette(), state -> state).encode(job.result(), null, false).brickCount());
        ContentView<String, String> capturedAir = mock(ContentView.class);
        when(capturedAir.isEmpty(any())).thenReturn(true);
        when(capturedAir.worldId()).thenReturn(UUID.randomUUID());
        when(capturedAir.sampleBiome(anyInt(), anyInt(), anyInt())).thenReturn("test:destination");
        when(capturedAir.getLight(anyInt(), anyInt(), anyInt())).thenReturn(ContentView.packLight(15, 0));
        ViewPlateBuilder.Job<String, Object> empty = ViewPlateBuilder.sectionJob(request.withDestView(capturedAir), clip);
        assertTrue(empty.step(1));
        verify(capturedAir, never()).sampleBlockData(anyInt(), anyInt(), anyInt());
        assertEquals(4096, empty.result().cellCount());
        assertEquals(ProjectorSample.Kind.REMOTE_AIR, empty.result().paletteCell(1).kind());
    }

    @Test
    void sentinelsReplaceBuriedAndBackingCellsAndAirFillsTheRest() {
        SyntheticWorld world = new SyntheticWorld(11L);
        ViewPlate<String> plate = smallPlate(world, false);
        SessionPalette palette = new SessionPalette();
        PlateStreamEncoder<String> encoder = new PlateStreamEncoder<String>(palette, state -> state);
        EncodedPlate encoded = encoder.encode(plate, null, false);

        BlockBox box = plate.box();
        assertEquals(box, encoded.cells());
        assertEquals(PlateSectionBox.snap(box), encoded.sections());
        assertEquals(encoded.sections().brickCount(), encoded.brickCount());
        int checked = 0;
        int occluded = 0;
        int backing = 0;
        for (int index = 0; index < encoded.brickCount(); index++) {
            Brick brick = encoded.brick(index);
            int[] cells = BrickCodec.unpack(brick);
            int baseX = encoded.sections().sectionX(index) << 4;
            int baseY = encoded.sections().sectionY(index) << 4;
            int baseZ = encoded.sections().sectionZ(index) << 4;
            for (int cell = 0; cell < cells.length; cell++) {
                int x = baseX + ViewStreamLimits.brickCellX(cell);
                int y = baseY + ViewStreamLimits.brickCellY(cell);
                int z = baseZ + ViewStreamLimits.brickCellZ(cell);
                PlateCell<String> source = box.index(x, y, z) < 0 ? null : plate.cell(CellKeys.pack(x, y, z));
                int expected;
                if (source == null) {
                    expected = ViewStreamLimits.PALETTE_AIR;
                } else if (source.kind() == ProjectorSample.Kind.OCCLUDED) {
                    expected = ViewStreamLimits.PALETTE_OCCLUDED;
                    occluded++;
                } else if (source.kind() == ProjectorSample.Kind.BACKING_BLOCK) {
                    expected = ViewStreamLimits.PALETTE_BACKING;
                    backing++;
                } else if (source.isAir()) {
                    expected = ViewStreamLimits.PALETTE_AIR;
                } else {
                    expected = palette.lookup(source.data());
                }
                assertEquals(expected, cells[cell], "cell " + x + "," + y + "," + z);
                checked++;
            }
        }
        assertEquals(encoded.brickCount() * ViewStreamLimits.BRICK_CELLS, checked);
        assertTrue(occluded > 0);
        assertTrue(backing > 0);
        int[] referenced = encoded.referencedIds();
        assertTrue(referenced.length > 1);
        for (int id : referenced) {
            assertTrue(id >= ViewStreamLimits.RESERVED_PALETTE_IDS && id < palette.size(), "referenced id " + id);
        }
    }

    @Test
    void backingStateIsTheMostCommonBackingSourceState() {
        SyntheticWorld world = new SyntheticWorld(12L);
        ViewPlate<String> plate = smallPlate(world, false);
        SessionPalette palette = new SessionPalette();
        PlateStreamEncoder<String> encoder = new PlateStreamEncoder<String>(palette, state -> state);
        EncodedPlate encoded = encoder.encode(plate, null, false);
        Map<String, Integer> votes = new HashMap<String, Integer>();
        for (long key : plate.cellKeys()) {
            PlateCell<String> cell = plate.cell(key);
            if (cell.kind() == ProjectorSample.Kind.BACKING_BLOCK) {
                votes.merge(cell.data(), 1, Integer::sum);
            }
        }
        String best = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : votes.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        assertNotNull(best);
        assertEquals(best, palette.state(encoded.backingState()));
    }

    @Test
    void aPlateWithoutBackingCellsNeverAdvertisesABuriedStateAsItsBackingState() {
        SyntheticWorld world = new SyntheticWorld(15L);
        BlockBox box = smallPlate(world, false).box();
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int corner = 0; corner < 8; corner++) {
            int[] remote = remoteOf((corner & 1) == 0 ? box.minX() : box.minX() + box.sizeX() - 1,
                (corner & 2) == 0 ? box.minY() : box.minY() + box.sizeY() - 1, (corner & 4) == 0 ? box.minZ() : box.minZ() + box.sizeZ() - 1);
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], remote[axis]);
                max[axis] = Math.max(max[axis], remote[axis]);
            }
        }
        for (int x = min[0] - 4; x <= max[0] + 4; x++) {
            for (int y = min[1] - 4; y <= max[1] + 4; y++) {
                for (int z = min[2] - 4; z <= max[2] + 4; z++) {
                    world.set(x, y, z, "minecraft:diamond_block");
                }
            }
        }
        ViewPlate<String> vault = smallPlate(world, false);
        int occluded = 0;
        for (long key : vault.cellKeys()) {
            ProjectorSample.Kind kind = vault.cell(key).kind();
            assertNotEquals(ProjectorSample.Kind.BACKING_BLOCK, kind);
            assertNotEquals(ProjectorSample.Kind.BLOCK, kind);
            if (kind == ProjectorSample.Kind.OCCLUDED) {
                occluded++;
            }
        }
        assertTrue(occluded > 0, "the vault plate must be buried");
        SessionPalette palette = new SessionPalette();
        EncodedPlate encoded = new PlateStreamEncoder<String>(palette, state -> state).encode(vault, null, false);

        assertEquals("minecraft:stone", palette.state(encoded.backingState()));
        assertTrue(palette.lookup("minecraft:diamond_block") < 0, "a buried state must never reach the session palette");
    }

    @Test
    void bytesAreCachedPerPlateAndHashesAreSalted() {
        SyntheticWorld world = new SyntheticWorld(13L);
        ViewPlate<String> plate = smallPlate(world, false);
        PlateStreamEncoder<String> encoder = new PlateStreamEncoder<String>(new SessionPalette(), state -> state);
        assertNull(encoder.cached(plate));
        EncodedPlate first = encoder.encode(plate, null, false);
        EncodedPlate second = encoder.encode(plate, null, false);
        assertSame(first, second);
        assertEquals(1L, encoder.encodes());
        long[] saltA = first.hashes(1L);
        long[] saltB = first.hashes(2L);
        assertEquals(first.brickCount(), saltA.length);
        assertFalse(Arrays.equals(saltA, saltB));
        assertSame(saltB, first.hashes(2L));
        encoder.forget(plate);
        assertNull(encoder.cached(plate));
        ViewPlate<String> again = smallPlate(world, false);
        EncodedPlate rebuilt = encoder.encode(again, null, false);
        assertArrayEquals(first.hashes(9L), rebuilt.hashes(9L));
        assertEquals(2L, encoder.encodes());
    }

    @Test
    void lightFollowsTheSourceOnEveryBrickAndBlockEntitiesRideAlong() throws IOException {
        SyntheticWorld world = new SyntheticWorld(14L);
        long visible = firstCellOfKind(smallPlate(world, false), ProjectorSample.Kind.BLOCK);
        int[] remote = remoteOf(CellKeys.unpackX(visible), CellKeys.unpackY(visible), CellKeys.unpackZ(visible));
        world.setBlockEntity(remote[0], remote[1], remote[2], "minecraft:chest[facing=north,type=single,waterlogged=false]",
            new BlockEntitySample("minecraft:chest", new byte[] {10, 0, 0, 0}));
        ViewPlate<String> plate = smallPlate(world, true);
        PlateStreamEncoder<String> encoder = new PlateStreamEncoder<String>(new SessionPalette(), state -> state);
        BrickLightSource light = (sectionX, sectionY, sectionZ, block, sky) -> {
            Arrays.fill(sky, (byte) 0xFF);
            return true;
        };
        EncodedPlate encoded = encoder.encode(plate, light, true);
        int lit = 0;
        int empty = 0;
        int withEntities = 0;
        for (Brick brick : encoded.bricks()) {
            assertTrue(brick.hasLight());
            lit++;
            if (brick.isEmpty()) {
                empty++;
                assertFalse(brick.hasBlockEntities());
            }
            if (brick.hasBlockEntities()) {
                withEntities++;
                assertEquals(1, brick.blockEntities().length);
                assertArrayEquals(new byte[] {10, 0, 0, 0}, BlockEntitySample.decode(brick.blockEntities()[0].payload()).nbt());
            }
        }
        assertTrue(lit > empty);
        assertEquals(1, withEntities);
        assertNotEquals(0L, encoded.totalBytes());
        PlateStreamEncoder<String> dark = new PlateStreamEncoder<String>(new SessionPalette(), state -> state);
        EncodedPlate unlit = dark.encode(smallPlate(world, true), (sectionX, sectionY, sectionZ, block, sky) -> false, true);
        for (Brick brick : unlit.bricks()) {
            assertFalse(brick.hasLight());
        }
    }

    @Test
    void emptyPlateEncodesToNoBricks() {
        ViewPlate<String> empty = new ViewPlate<String>(new ViewPlateKey(UUID.randomUUID(), "view", true, 0, 0L),
            PlateGrid.<String>empty(), 0L, 0L, null, Long.MIN_VALUE, 0, 0, -1, -1, 0L, null);
        SessionPalette palette = new SessionPalette();
        PlateStreamEncoder<String> encoder = new PlateStreamEncoder<String>(palette, state -> state);
        EncodedPlate encoded = encoder.encode(empty, null, true);
        assertEquals(0, encoded.brickCount());
        assertEquals(PlateSectionBox.EMPTY, encoded.sections());
        assertEquals("minecraft:stone", palette.state(encoded.backingState()));
        assertEquals(List.of(), encoded.bricks());
    }
}
