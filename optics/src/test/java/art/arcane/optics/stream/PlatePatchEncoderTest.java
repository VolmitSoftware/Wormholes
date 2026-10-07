package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.optics.internal.stream.EncodedPlate;
import art.arcane.optics.internal.stream.PlatePatchEncoder;
import art.arcane.optics.internal.stream.PlateStreamEncoder;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import art.arcane.optics.math.BlockBox;
import art.arcane.optics.plate.ViewPlate;

final class PlatePatchEncoderTest {
    @Test
    void aUniformReplacementUsesFewerBytesThanSparseCellEdits() throws ViewStreamProtocolException {
        int[] cells = new int[ViewStreamLimits.BRICK_CELLS];
        Arrays.fill(cells, 3);
        Brick target = BrickCodec.pack(0, cells);
        Arrays.fill(cells, 0, 32, 4);
        Brick source = BrickCodec.pack(0, cells);
        ViewStreamMessage.PlatePatch patch = PlatePatchEncoder.diff(1, 1, 2, singleBrick(source), singleBrick(target));
        ViewStreamMessage.PlatePatch full = new ViewStreamMessage.PlatePatch(1, 1, 2, List.of(new ViewStreamMessage.FullOp(target)));

        assertTrue(ViewStreamFixtures.CODEC.encodeBody(patch).length <= ViewStreamFixtures.CODEC.encodeBody(full).length);
        assertInstanceOf(ViewStreamMessage.FullOp.class, patch.ops().getFirst());
        assertEquals(List.of(target), List.of(PlatePatchEncoder.apply(new Brick[] {source}, patch)));
    }

    @Test
    void anIsolatedEditKeepsTheSmallerSparseRepresentation() throws ViewStreamProtocolException {
        int[] cells = new int[ViewStreamLimits.BRICK_CELLS];
        for (int index = 0; index < cells.length; index++) {
            cells[index] = 3 + index % 4;
        }
        Brick source = BrickCodec.pack(0, cells);
        cells[200] = 7;
        Brick target = BrickCodec.pack(0, cells);
        ViewStreamMessage.PlatePatch patch = PlatePatchEncoder.diff(1, 1, 2, singleBrick(source), singleBrick(target));
        ViewStreamMessage.PlatePatch full = new ViewStreamMessage.PlatePatch(1, 1, 2, List.of(new ViewStreamMessage.FullOp(target)));

        assertTrue(ViewStreamFixtures.CODEC.encodeBody(patch).length < ViewStreamFixtures.CODEC.encodeBody(full).length);
        assertInstanceOf(ViewStreamMessage.SparseOp.class, patch.ops().getFirst());
        assertEquals(List.of(target), List.of(PlatePatchEncoder.apply(new Brick[] {source}, patch)));
    }

    @Test
    void patchAppliedToThePreviousBricksEqualsTheFullReEncodeForRandomDirt() throws ViewStreamProtocolException {
        Random random = new Random(0xD127L);
        SessionPalette palette = new SessionPalette();
        PlateStreamEncoder<String> encoder = new PlateStreamEncoder<String>(palette, state -> state);
        int sparse = 0;
        int full = 0;
        int clear = 0;
        for (int round = 0; round < 12; round++) {
            SyntheticWorld world = new SyntheticWorld(5000L + round);
            ViewPlate<String> before = PlateStreamEncoderTest.smallPlate(world, false);
            EncodedPlate previous = encoder.encode(before, null, false);
            int edits = round % 3 == 0 ? 1 + random.nextInt(8) : 40 + random.nextInt(400);
            for (int edit = 0; edit < edits; edit++) {
                int x = 200 + random.nextInt(24) - 12;
                int z = 200 + random.nextInt(24) - 12;
                int y = 55 + random.nextInt(24);
                String state = random.nextInt(5) == 0 ? SyntheticWorld.AIR : random.nextBoolean() ? "minecraft:gold_block" : "minecraft:glass";
                world.set(x, y, z, state);
            }
            ViewPlate<String> after = PlateStreamEncoderTest.smallPlate(world, false);
            EncodedPlate next = encoder.encode(after, null, false);
            ViewStreamMessage.PlatePatch patch = PlatePatchEncoder.diff(3, round, round + 1, previous, next);
            for (ViewStreamMessage.PatchOp op : patch.ops()) {
                switch (op) {
                    case ViewStreamMessage.SparseOp s -> {
                        sparse++;
                        assertTrue(s.cellIndices().length < ViewStreamLimits.SPARSE_PATCH_MAX_CELLS);
                    }
                    case ViewStreamMessage.FullOp f -> full++;
                    case ViewStreamMessage.ClearOp c -> clear++;
                }
            }
            Brick[] patched = PlatePatchEncoder.apply(previous.bricks().toArray(new Brick[0]), patch);
            assertEquals(next.bricks(), List.of(patched), "round " + round);
            byte[] frame = ViewStreamFixtures.CODEC.encodeS2C(patch, round, ViewStreamLimits.FLAG_LAST);
            assertEquals(patch, ViewStreamFixtures.CODEC.decodeS2C(frame, ViewStreamCapability.ALL).message());
        }
        assertTrue(sparse > 0, "no sparse ops were produced");
        assertTrue(full > 0, "no full ops were produced");
        assertTrue(clear >= 0);
    }

    @Test
    void unchangedPlatesProduceAnEmptyPatchAndClearedBricksProduceClearOps() throws ViewStreamProtocolException {
        SessionPalette palette = new SessionPalette();
        PlateStreamEncoder<String> encoder = new PlateStreamEncoder<String>(palette, state -> state);
        SyntheticWorld world = new SyntheticWorld(77L);
        ViewPlate<String> plate = PlateStreamEncoderTest.smallPlate(world, false);
        EncodedPlate encoded = encoder.encode(plate, null, false);
        assertTrue(PlatePatchEncoder.diff(1, 1, 2, encoded, encoded).ops().isEmpty());

        int filled = -1;
        for (int index = 0; index < encoded.brickCount(); index++) {
            if (!encoded.brick(index).isEmpty()) {
                filled = index;
                break;
            }
        }
        assertFalse(filled < 0);
        int baseX = encoded.sections().sectionX(filled) << 4;
        int baseY = encoded.sections().sectionY(filled) << 4;
        int baseZ = encoded.sections().sectionZ(filled) << 4;
        for (int x = -1; x < 17; x++) {
            for (int y = -1; y < 17; y++) {
                for (int z = -1; z < 17; z++) {
                    int[] remote = PlateStreamEncoderTest.remoteOf(baseX + x, baseY + y, baseZ + z);
                    world.set(remote[0], remote[1], remote[2], SyntheticWorld.AIR);
                }
            }
        }
        EncodedPlate next = encoder.encode(PlateStreamEncoderTest.smallPlate(world, false), null, false);
        ViewStreamMessage.PlatePatch patch = PlatePatchEncoder.diff(1, 1, 2, encoded, next);
        boolean cleared = false;
        for (ViewStreamMessage.PatchOp op : patch.ops()) {
            if (op.brickIndex() == filled) {
                assertInstanceOf(ViewStreamMessage.ClearOp.class, op);
                cleared = true;
            }
        }
        assertTrue(cleared);
        assertEquals(next.bricks(), List.of(PlatePatchEncoder.apply(encoded.bricks().toArray(new Brick[0]), patch)));
    }

    @Test
    void emptiedBricksThatStillCarryLightTravelAsFullOps() throws ViewStreamProtocolException {
        PlateStreamEncoder<String> encoder = new PlateStreamEncoder<String>(new SessionPalette(), state -> state);
        BrickLightSource light = (sectionX, sectionY, sectionZ, block, sky) -> {
            Arrays.fill(sky, (byte) 0x77);
            return true;
        };
        SyntheticWorld world = new SyntheticWorld(78L);
        EncodedPlate encoded = encoder.encode(PlateStreamEncoderTest.smallPlate(world, false), light, false);
        int filled = -1;
        for (int index = 0; index < encoded.brickCount(); index++) {
            if (!encoded.brick(index).isEmpty()) {
                filled = index;
                break;
            }
        }
        assertFalse(filled < 0);
        int baseX = encoded.sections().sectionX(filled) << 4;
        int baseY = encoded.sections().sectionY(filled) << 4;
        int baseZ = encoded.sections().sectionZ(filled) << 4;
        for (int x = -1; x < 17; x++) {
            for (int y = -1; y < 17; y++) {
                for (int z = -1; z < 17; z++) {
                    int[] remote = PlateStreamEncoderTest.remoteOf(baseX + x, baseY + y, baseZ + z);
                    world.set(remote[0], remote[1], remote[2], SyntheticWorld.AIR);
                }
            }
        }
        EncodedPlate next = encoder.encode(PlateStreamEncoderTest.smallPlate(world, false), light, false);
        ViewStreamMessage.PlatePatch patch = PlatePatchEncoder.diff(1, 1, 2, encoded, next);
        boolean full = false;
        for (ViewStreamMessage.PatchOp op : patch.ops()) {
            if (op.brickIndex() == filled) {
                ViewStreamMessage.FullOp fullOp = assertInstanceOf(ViewStreamMessage.FullOp.class, op);
                assertTrue(fullOp.brick().isEmpty());
                assertTrue(fullOp.brick().hasLight());
                full = true;
            }
        }
        assertTrue(full);
        assertEquals(next.bricks(), List.of(PlatePatchEncoder.apply(encoded.bricks().toArray(new Brick[0]), patch)));
    }

    private static EncodedPlate singleBrick(Brick brick) throws ViewStreamProtocolException {
        BlockBox cells = new BlockBox(0, 0, 0, 16, 16, 16);
        return new EncodedPlate(PlateSectionBox.snap(cells), cells, 3, new Brick[] {brick},
            new byte[][] {BrickCodec.body(brick)}, new int[] {3, 4, 5, 6, 7}, new int[0]);
    }
}
