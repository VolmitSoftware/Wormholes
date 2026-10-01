package art.arcane.wormholes.network.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.ProjectorFrameTransform;
import art.arcane.wormholes.render.ProjectorSample;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.lod.LodPolicy;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.render.plate.PlateCell;
import art.arcane.wormholes.render.plate.PlateGrid;
import art.arcane.wormholes.render.plate.ViewPlate;
import art.arcane.wormholes.render.plate.ViewPlateBuilder;
import art.arcane.wormholes.render.plate.ViewPlateKey;
import art.arcane.wormholes.render.view.ProjectionContentView;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

final class PlateStreamEncoderTest {
    static int[] remoteOf(int localX, int localY, int localZ) {
        ProjectorFrameTransform transform = new ProjectorFrameTransform();
        transform.configure(PortalFrame.canonical(Direction.S).view(false), PortalFrame.canonical(Direction.N).view(false),
            11.4995D, 67.4995D, 20.5005D, 200.4995D, 67.4995D, 200.4995D);
        double[] out = new double[3];
        transform.apply(localX + 0.5D, localY + 0.5D, localZ + 0.5D, out);
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
        PortalGeometry geometry = new PortalGeometry();
        geometry.setArea(new AxisAlignedBB(10, 12.999, 66, 68.999, 20, 20.999));
        ViewPlateKey key = new ViewPlateKey(UUID.nameUUIDFromBytes("small".getBytes()), world, false, 0, 0L);
        ViewPlateBuilder.Request<String, String, ProjectionContentView<String, String>> request =
            new ViewPlateBuilder.Request<String, String, ProjectionContentView<String, String>>(key, geometry, world,
                PortalFrame.canonical(Direction.S), PortalFrame.canonical(Direction.N), 11.4995, 67.4995, 20.5005,
                200.4995, 67.4995, 200.4995, false, 0, 24.0D, 8.0D, 0.75D, true, SyntheticWorld.AIR, LodPolicy.NONE, blockEntities,
                0L, 0L, 0L, SyntheticBlocks.INSTANCE);
        return ViewPlateBuilder.build(request);
    }

    @Test
    void sentinelsReplaceBuriedAndBackingCellsAndAirFillsTheRest() {
        SyntheticWorld world = new SyntheticWorld(11L);
        ViewPlate<String> plate = smallPlate(world, false);
        SessionPalette palette = new SessionPalette();
        PlateStreamEncoder<String> encoder = new PlateStreamEncoder<String>(palette, state -> state);
        EncodedPlate encoded = encoder.encode(plate, null, false);

        PlateBox box = plate.box();
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
                int x = baseX + ClientViewProtocol.brickCellX(cell);
                int y = baseY + ClientViewProtocol.brickCellY(cell);
                int z = baseZ + ClientViewProtocol.brickCellZ(cell);
                PlateCell<String> source = box.index(x, y, z) < 0 ? null : plate.cell(ProjectionCellKey.pack(x, y, z));
                int expected;
                if (source == null) {
                    expected = ClientViewProtocol.PALETTE_AIR;
                } else if (source.kind() == ProjectorSample.Kind.OCCLUDED) {
                    expected = ClientViewProtocol.PALETTE_OCCLUDED;
                    occluded++;
                } else if (source.kind() == ProjectorSample.Kind.BACKING_BLOCK) {
                    expected = ClientViewProtocol.PALETTE_BACKING;
                    backing++;
                } else if (source.isAir()) {
                    expected = ClientViewProtocol.PALETTE_AIR;
                } else {
                    expected = palette.lookup(source.data());
                }
                assertEquals(expected, cells[cell], "cell " + x + "," + y + "," + z);
                checked++;
            }
        }
        assertEquals(encoded.brickCount() * ClientViewProtocol.BRICK_CELLS, checked);
        assertTrue(occluded > 0);
        assertTrue(backing > 0);
        int[] referenced = encoded.referencedIds();
        assertTrue(referenced.length > 1);
        for (int id : referenced) {
            assertTrue(id >= ClientViewProtocol.RESERVED_PALETTE_IDS && id < palette.size(), "referenced id " + id);
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
        PlateBox box = smallPlate(world, false).box();
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
        int[] remote = remoteOf(ProjectionCellKey.unpackX(visible), ProjectionCellKey.unpackY(visible), ProjectionCellKey.unpackZ(visible));
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
            PlateGrid.<String>empty(), 0L, 0L, null, Long.MIN_VALUE, 0, 0, -1, -1, 0L);
        SessionPalette palette = new SessionPalette();
        PlateStreamEncoder<String> encoder = new PlateStreamEncoder<String>(palette, state -> state);
        EncodedPlate encoded = encoder.encode(empty, null, true);
        assertEquals(0, encoded.brickCount());
        assertEquals(PlateSectionBox.EMPTY, encoded.sections());
        assertEquals("minecraft:stone", palette.state(encoded.backingState()));
        assertEquals(List.of(), encoded.bricks());
    }
}
