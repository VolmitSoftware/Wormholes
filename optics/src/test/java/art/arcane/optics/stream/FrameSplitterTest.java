package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.IntSupplier;

import org.junit.jupiter.api.Test;

import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.internal.stream.FrameSplitter;
import art.arcane.optics.internal.stream.PlatePatchEncoder;
import art.arcane.optics.math.BlockBox;

final class FrameSplitterTest {
    private static List<Brick> heavyBricks(int count, Random random) {
        List<Brick> bricks = new ArrayList<Brick>(count);
        for (int i = 0; i < count; i++) {
            int[] cells = new int[ViewStreamLimits.BRICK_CELLS];
            for (int c = 0; c < cells.length; c++) {
                cells[c] = 3 + random.nextInt(300);
            }
            byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
            byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
            random.nextBytes(block);
            bricks.add(BrickCodec.pack(i, cells).withLight(block, sky));
        }
        return bricks;
    }

    @Test
    void groupsNeverExceedTheFrameSizeAndOnlyTheLastFrameCarriesLast() throws ViewStreamProtocolException {
        Random random = new Random(3L);
        List<Brick> bricks = heavyBricks(40, random);
        ViewStreamMessage.PlateBricks message = new ViewStreamMessage.PlateBricks(2, 9, bricks);
        FrameSplitter splitter = new FrameSplitter(ViewStreamLimits.MIN_MAX_FRAME_BYTES, false, ViewStreamFixtures.CODEC);
        int[] next = {100};
        IntSupplier sequence = () -> next[0]++;
        List<ViewStreamMessage> group = List.of(new ViewStreamMessage.PlateBegin(2, 9, new PlateSectionBox(0, 0, 0, 5, 2, 4),
            new BlockBox(0, 0, 0, 80, 32, 64), 3, 40, null), message, new ViewStreamMessage.PlateEnd(2, 9));
        List<byte[]> frames = splitter.split(group, sequence);
        assertTrue(frames.size() > 3, "expected the bricks to split into several frames, got " + frames.size());
        List<Brick> reassembled = new ArrayList<Brick>();
        for (int i = 0; i < frames.size(); i++) {
            byte[] frame = frames.get(i);
            assertTrue(frame.length <= ViewStreamLimits.MIN_MAX_FRAME_BYTES, "frame " + i + " is " + frame.length + " bytes");
            ViewStreamCodec.S2CFrame decoded = ViewStreamFixtures.CODEC.decodeS2C(frame, ViewStreamCapability.NONE);
            assertEquals(100 + i, decoded.seq());
            assertEquals(i == frames.size() - 1, decoded.last(), "frame " + i);
            if (decoded.message() instanceof ViewStreamMessage.PlateBricks part) {
                reassembled.addAll(part.bricks());
            }
        }
        assertEquals(bricks, reassembled);
        assertEquals(100 + frames.size(), next[0]);
    }

    @Test
    void anOpenGroupCarriesNoLastFlag() throws ViewStreamProtocolException {
        Random random = new Random(4L);
        FrameSplitter splitter = new FrameSplitter(ViewStreamLimits.MIN_MAX_FRAME_BYTES, false, ViewStreamFixtures.CODEC);
        int[] next = {7};
        List<ViewStreamMessage> group = List.of(new ViewStreamMessage.PlateBegin(2, 9, new PlateSectionBox(0, 0, 0, 5, 2, 4),
            new BlockBox(0, 0, 0, 80, 32, 64), 3, 40, null), new ViewStreamMessage.PlateBricks(2, 9, heavyBricks(40, random)));
        List<byte[]> open = splitter.split(group, () -> next[0]++, false);
        assertTrue(open.size() > 2);
        for (byte[] frame : open) {
            assertTrue(frame.length <= ViewStreamLimits.MIN_MAX_FRAME_BYTES);
            assertFalse(ViewStreamFixtures.CODEC.decodeS2C(frame, ViewStreamCapability.NONE).last());
        }
        List<byte[]> closed = splitter.split(List.of(new ViewStreamMessage.PlateEnd(2, 9)), () -> next[0]++, true);
        assertEquals(1, closed.size());
        assertTrue(ViewStreamFixtures.CODEC.decodeS2C(closed.get(0), ViewStreamCapability.NONE).last());
        assertEquals(7 + open.size() + 1, next[0]);
    }

    @Test
    void aPatchLargerThanAFrameSplitsIntoPiecesAndOnlyTheClosingPieceAdvancesTheRevision() throws ViewStreamProtocolException {
        Random random = new Random(5L);
        List<Brick> bricks = heavyBricks(40, random);
        List<ViewStreamMessage.PatchOp> ops = new ArrayList<ViewStreamMessage.PatchOp>(bricks.size());
        for (Brick brick : bricks) {
            ops.add(new ViewStreamMessage.FullOp(brick));
        }
        ViewStreamMessage.PlatePatch patch = new ViewStreamMessage.PlatePatch(2, 9, 10, ops);
        FrameSplitter splitter = new FrameSplitter(ViewStreamLimits.MIN_MAX_FRAME_BYTES, false, ViewStreamFixtures.CODEC);
        int[] next = {0};
        List<byte[]> frames = splitter.split(List.of(patch), () -> next[0]++);
        assertTrue(frames.size() > 1, "expected the patch to split, got " + frames.size());
        List<ViewStreamMessage.PatchOp> reassembled = new ArrayList<ViewStreamMessage.PatchOp>();
        Brick[] applied = new Brick[bricks.size()];
        for (int i = 0; i < applied.length; i++) {
            applied[i] = Brick.empty(i);
        }
        int revision = 9;
        for (int i = 0; i < frames.size(); i++) {
            ViewStreamCodec.S2CFrame decoded = ViewStreamFixtures.CODEC.decodeS2C(frames.get(i), ViewStreamCapability.NONE);
            ViewStreamMessage.PlatePatch piece = (ViewStreamMessage.PlatePatch) decoded.message();
            boolean closing = i == frames.size() - 1;
            assertEquals(closing, decoded.last(), "frame " + i);
            assertEquals(9, piece.fromRevision(), "every piece starts at the revision the client holds");
            assertEquals(closing ? 10 : 9, piece.toRevision(), "frame " + i);
            assertEquals(closing, piece.advances());
            assertEquals(revision, piece.fromRevision());
            applied = PlatePatchEncoder.apply(applied, piece);
            revision = piece.toRevision();
            reassembled.addAll(piece.ops());
        }
        assertEquals(ops, reassembled);
        assertEquals(10, revision);
        assertEquals(bricks, List.of(applied));
    }

    @Test
    void deflatedFramesAlsoRespectTheCapAndDecode() throws ViewStreamProtocolException {
        Random random = new Random(4L);
        ViewStreamMessage.PlateBricks message = new ViewStreamMessage.PlateBricks(2, 9, heavyBricks(12, random));
        FrameSplitter splitter = new FrameSplitter(ViewStreamLimits.MIN_MAX_FRAME_BYTES, true, ViewStreamFixtures.CODEC);
        int[] next = {0};
        List<byte[]> frames = splitter.split(List.of(message), () -> next[0]++);
        List<Brick> reassembled = new ArrayList<Brick>();
        boolean deflated = false;
        for (byte[] frame : frames) {
            assertTrue(frame.length <= ViewStreamLimits.MIN_MAX_FRAME_BYTES);
            ViewStreamCodec.S2CFrame decoded = ViewStreamFixtures.CODEC.decodeS2C(frame, ViewStreamCapability.NONE);
            deflated |= (decoded.flags() & ViewStreamLimits.FLAG_DEFLATED) != 0;
            reassembled.addAll(((ViewStreamMessage.PlateBricks) decoded.message()).bricks());
        }
        assertEquals(message.bricks(), reassembled);
        assertFalse(frames.isEmpty());
        assertTrue(deflated || frames.size() == 1);
    }

    @Test
    void paletteAndPatchGroupsSplitAtElementBoundaries() throws ViewStreamProtocolException {
        List<ViewStreamMessage.PaletteEntry> entries = new ArrayList<ViewStreamMessage.PaletteEntry>();
        for (int i = 0; i < 6000; i++) {
            entries.add(new ViewStreamMessage.PaletteEntry(3 + i, "minecraft:block_" + i + "[facing=north,half=top,waterlogged=false]"));
        }
        List<ViewStreamMessage.PatchOp> ops = new ArrayList<ViewStreamMessage.PatchOp>();
        Random random = new Random(5L);
        for (Brick brick : heavyBricks(20, random)) {
            ops.add(new ViewStreamMessage.FullOp(brick));
            ops.add(new ViewStreamMessage.ClearOp(brick.brickIndex()));
        }
        FrameSplitter splitter = new FrameSplitter(ViewStreamLimits.MIN_MAX_FRAME_BYTES, false, ViewStreamFixtures.CODEC);
        int[] next = {0};
        List<byte[]> frames = splitter.split(List.of(new ViewStreamMessage.Palette(entries), new ViewStreamMessage.PlatePatch(1, 1, 2, ops)),
            () -> next[0]++);
        List<ViewStreamMessage.PaletteEntry> palette = new ArrayList<ViewStreamMessage.PaletteEntry>();
        List<ViewStreamMessage.PatchOp> patch = new ArrayList<ViewStreamMessage.PatchOp>();
        for (byte[] frame : frames) {
            assertTrue(frame.length <= ViewStreamLimits.MIN_MAX_FRAME_BYTES);
            ViewStreamMessage decoded = ViewStreamFixtures.CODEC.decodeS2C(frame, ViewStreamCapability.NONE).message();
            if (decoded instanceof ViewStreamMessage.Palette p) {
                palette.addAll(p.entries());
            } else {
                patch.addAll(((ViewStreamMessage.PlatePatch) decoded).ops());
            }
        }
        assertEquals(entries, palette);
        assertEquals(ops, patch);
        assertTrue(frames.size() >= 4);
    }

    @Test
    void anOversizeEntityFrameSplitsWithThePresenceListOnlyOnTheLastPiece() throws ViewStreamProtocolException {
        List<EntitySnapshot> visuals = new ArrayList<EntitySnapshot>();
        List<UUID> present = new ArrayList<UUID>();
        byte[] blob = new byte[8 * 1024];
        for (int i = 0; i < 40; i++) {
            UUID id = new UUID(i, i);
            visuals.add(EntitySnapshot.full(id, "minecraft:pig", 0.0D, 64.0D, 0.0D, 1.0D, 0.0D, 0.0D, 1.0D, 0.0F, 0.0F,
                0.0D, 0.0D, 0.0D, true, null, null, null, null, null, blob, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, i));
            present.add(id);
        }
        for (int i = 0; i < ViewStreamLimits.MAX_PRESENT_IDS_PER_FRAME - 40; i++) {
            present.add(new UUID(-1L, i));
        }
        FrameSplitter splitter = new FrameSplitter(1, false, ViewStreamFixtures.CODEC);
        assertEquals(ViewStreamLimits.MIN_MAX_FRAME_BYTES, splitter.maxFrameBytes());

        List<ViewStreamMessage.EntityFrame> pieces = entityPieces(splitter, new ViewStreamMessage.EntityFrame(1, 5, visuals, present, true));

        assertTrue(pieces.size() > 1, "expected the frame to split, got " + pieces.size());
        List<EntitySnapshot> reassembled = new ArrayList<EntitySnapshot>();
        for (int i = 0; i < pieces.size(); i++) {
            ViewStreamMessage.EntityFrame piece = pieces.get(i);
            boolean closing = i == pieces.size() - 1;
            assertEquals(1, piece.portalKey());
            assertEquals(5, piece.entitySeq());
            assertEquals(closing, piece.presence(), "piece " + i);
            assertEquals(closing ? present : List.of(), piece.presentIds(), "piece " + i);
            reassembled.addAll(piece.entities());
        }
        assertEntities(visuals, reassembled);

        List<ViewStreamMessage.EntityFrame> unchanged = entityPieces(splitter, new ViewStreamMessage.EntityFrame(1, 6, visuals, List.of(), false));
        assertTrue(unchanged.size() > 1);
        List<EntitySnapshot> deltas = new ArrayList<EntitySnapshot>();
        for (ViewStreamMessage.EntityFrame piece : unchanged) {
            assertFalse(piece.presence());
            deltas.addAll(piece.entities());
        }
        assertEntities(visuals, deltas);

        ViewStreamMessage.EntityFrame small = new ViewStreamMessage.EntityFrame(1, 1, visuals.subList(0, 2), List.of(), true);
        assertEquals(1, splitter.split(List.of(small), () -> 0).size());
    }

    private static List<ViewStreamMessage.EntityFrame> entityPieces(FrameSplitter splitter, ViewStreamMessage.EntityFrame frame)
        throws ViewStreamProtocolException {
        int[] next = {0};
        List<byte[]> frames = splitter.split(List.of(frame), () -> next[0]++);
        List<ViewStreamMessage.EntityFrame> pieces = new ArrayList<ViewStreamMessage.EntityFrame>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            byte[] encoded = frames.get(i);
            assertTrue(encoded.length <= splitter.maxFrameBytes(), "frame " + i + " is " + encoded.length + " bytes");
            ViewStreamCodec.S2CFrame decoded = ViewStreamFixtures.CODEC.decodeS2C(encoded, ViewStreamCapability.NONE);
            assertEquals(i == frames.size() - 1, decoded.last(), "frame " + i);
            pieces.add((ViewStreamMessage.EntityFrame) decoded.message());
        }
        return pieces;
    }

    private static void assertEntities(List<EntitySnapshot> expected, List<EntitySnapshot> actual) throws ViewStreamProtocolException {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(ViewStreamCodec.entityBytes(expected.get(i)), ViewStreamCodec.entityBytes(actual.get(i)), "entity " + i);
        }
    }
}
