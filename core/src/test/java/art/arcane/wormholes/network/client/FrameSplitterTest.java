package art.arcane.wormholes.network.client;

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
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.BrickCodec;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.PlateSectionBox;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;

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
        ClientViewMessage.PlateBricks message = new ClientViewMessage.PlateBricks(2, 9, bricks);
        FrameSplitter splitter = new FrameSplitter(ViewStreamLimits.MIN_MAX_FRAME_BYTES, false);
        int[] next = {100};
        IntSupplier sequence = () -> next[0]++;
        List<ClientViewMessage> group = List.of(new ClientViewMessage.PlateBegin(2, 9, new PlateSectionBox(0, 0, 0, 5, 2, 4),
            new BlockBox(0, 0, 0, 80, 32, 64), 3, 40, null), message, new ClientViewMessage.PlateEnd(2, 9));
        List<byte[]> frames = splitter.split(group, sequence);
        assertTrue(frames.size() > 3, "expected the bricks to split into several frames, got " + frames.size());
        List<Brick> reassembled = new ArrayList<Brick>();
        for (int i = 0; i < frames.size(); i++) {
            byte[] frame = frames.get(i);
            assertTrue(frame.length <= ViewStreamLimits.MIN_MAX_FRAME_BYTES, "frame " + i + " is " + frame.length + " bytes");
            ClientViewCodec.S2CFrame decoded = ClientViewCodec.decodeS2C(frame, ViewStreamCapability.NONE);
            assertEquals(100 + i, decoded.seq());
            assertEquals(i == frames.size() - 1, decoded.last(), "frame " + i);
            if (decoded.message() instanceof ClientViewMessage.PlateBricks part) {
                reassembled.addAll(part.bricks());
            }
        }
        assertEquals(bricks, reassembled);
        assertEquals(100 + frames.size(), next[0]);
    }

    @Test
    void anOpenGroupCarriesNoLastFlag() throws ViewStreamProtocolException {
        Random random = new Random(4L);
        FrameSplitter splitter = new FrameSplitter(ViewStreamLimits.MIN_MAX_FRAME_BYTES, false);
        int[] next = {7};
        List<ClientViewMessage> group = List.of(new ClientViewMessage.PlateBegin(2, 9, new PlateSectionBox(0, 0, 0, 5, 2, 4),
            new BlockBox(0, 0, 0, 80, 32, 64), 3, 40, null), new ClientViewMessage.PlateBricks(2, 9, heavyBricks(40, random)));
        List<byte[]> open = splitter.split(group, () -> next[0]++, false);
        assertTrue(open.size() > 2);
        for (byte[] frame : open) {
            assertTrue(frame.length <= ViewStreamLimits.MIN_MAX_FRAME_BYTES);
            assertFalse(ClientViewCodec.decodeS2C(frame, ViewStreamCapability.NONE).last());
        }
        List<byte[]> closed = splitter.split(List.of(new ClientViewMessage.PlateEnd(2, 9)), () -> next[0]++, true);
        assertEquals(1, closed.size());
        assertTrue(ClientViewCodec.decodeS2C(closed.get(0), ViewStreamCapability.NONE).last());
        assertEquals(7 + open.size() + 1, next[0]);
    }

    @Test
    void aPatchLargerThanAFrameSplitsIntoPiecesAndOnlyTheClosingPieceAdvancesTheRevision() throws ViewStreamProtocolException {
        Random random = new Random(5L);
        List<Brick> bricks = heavyBricks(40, random);
        List<ClientViewMessage.PatchOp> ops = new ArrayList<ClientViewMessage.PatchOp>(bricks.size());
        for (Brick brick : bricks) {
            ops.add(new ClientViewMessage.FullOp(brick));
        }
        ClientViewMessage.PlatePatch patch = new ClientViewMessage.PlatePatch(2, 9, 10, ops);
        FrameSplitter splitter = new FrameSplitter(ViewStreamLimits.MIN_MAX_FRAME_BYTES, false);
        int[] next = {0};
        List<byte[]> frames = splitter.split(List.of(patch), () -> next[0]++);
        assertTrue(frames.size() > 1, "expected the patch to split, got " + frames.size());
        List<ClientViewMessage.PatchOp> reassembled = new ArrayList<ClientViewMessage.PatchOp>();
        Brick[] applied = new Brick[bricks.size()];
        for (int i = 0; i < applied.length; i++) {
            applied[i] = Brick.empty(i);
        }
        int revision = 9;
        for (int i = 0; i < frames.size(); i++) {
            ClientViewCodec.S2CFrame decoded = ClientViewCodec.decodeS2C(frames.get(i), ViewStreamCapability.NONE);
            ClientViewMessage.PlatePatch piece = (ClientViewMessage.PlatePatch) decoded.message();
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
        ClientViewMessage.PlateBricks message = new ClientViewMessage.PlateBricks(2, 9, heavyBricks(12, random));
        FrameSplitter splitter = new FrameSplitter(ViewStreamLimits.MIN_MAX_FRAME_BYTES, true);
        int[] next = {0};
        List<byte[]> frames = splitter.split(List.of(message), () -> next[0]++);
        List<Brick> reassembled = new ArrayList<Brick>();
        boolean deflated = false;
        for (byte[] frame : frames) {
            assertTrue(frame.length <= ViewStreamLimits.MIN_MAX_FRAME_BYTES);
            ClientViewCodec.S2CFrame decoded = ClientViewCodec.decodeS2C(frame, ViewStreamCapability.NONE);
            deflated |= (decoded.flags() & ViewStreamLimits.FLAG_DEFLATED) != 0;
            reassembled.addAll(((ClientViewMessage.PlateBricks) decoded.message()).bricks());
        }
        assertEquals(message.bricks(), reassembled);
        assertFalse(frames.isEmpty());
        assertTrue(deflated || frames.size() == 1);
    }

    @Test
    void paletteAndPatchGroupsSplitAtElementBoundaries() throws ViewStreamProtocolException {
        List<ClientViewMessage.PaletteEntry> entries = new ArrayList<ClientViewMessage.PaletteEntry>();
        for (int i = 0; i < 6000; i++) {
            entries.add(new ClientViewMessage.PaletteEntry(3 + i, "minecraft:block_" + i + "[facing=north,half=top,waterlogged=false]"));
        }
        List<ClientViewMessage.PatchOp> ops = new ArrayList<ClientViewMessage.PatchOp>();
        Random random = new Random(5L);
        for (Brick brick : heavyBricks(20, random)) {
            ops.add(new ClientViewMessage.FullOp(brick));
            ops.add(new ClientViewMessage.ClearOp(brick.brickIndex()));
        }
        FrameSplitter splitter = new FrameSplitter(ViewStreamLimits.MIN_MAX_FRAME_BYTES, false);
        int[] next = {0};
        List<byte[]> frames = splitter.split(List.of(new ClientViewMessage.Palette(entries), new ClientViewMessage.PlatePatch(1, 1, 2, ops)),
            () -> next[0]++);
        List<ClientViewMessage.PaletteEntry> palette = new ArrayList<ClientViewMessage.PaletteEntry>();
        List<ClientViewMessage.PatchOp> patch = new ArrayList<ClientViewMessage.PatchOp>();
        for (byte[] frame : frames) {
            assertTrue(frame.length <= ViewStreamLimits.MIN_MAX_FRAME_BYTES);
            ClientViewMessage decoded = ClientViewCodec.decodeS2C(frame, ViewStreamCapability.NONE).message();
            if (decoded instanceof ClientViewMessage.Palette p) {
                palette.addAll(p.entries());
            } else {
                patch.addAll(((ClientViewMessage.PlatePatch) decoded).ops());
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
        FrameSplitter splitter = new FrameSplitter(1, false);
        assertEquals(ViewStreamLimits.MIN_MAX_FRAME_BYTES, splitter.maxFrameBytes());

        List<ClientViewMessage.EntityFrame> pieces = entityPieces(splitter, new ClientViewMessage.EntityFrame(1, 5, visuals, present, true));

        assertTrue(pieces.size() > 1, "expected the frame to split, got " + pieces.size());
        List<EntitySnapshot> reassembled = new ArrayList<EntitySnapshot>();
        for (int i = 0; i < pieces.size(); i++) {
            ClientViewMessage.EntityFrame piece = pieces.get(i);
            boolean closing = i == pieces.size() - 1;
            assertEquals(1, piece.portalKey());
            assertEquals(5, piece.entitySeq());
            assertEquals(closing, piece.presence(), "piece " + i);
            assertEquals(closing ? present : List.of(), piece.presentIds(), "piece " + i);
            reassembled.addAll(piece.entities());
        }
        assertEntities(visuals, reassembled);

        List<ClientViewMessage.EntityFrame> unchanged = entityPieces(splitter, new ClientViewMessage.EntityFrame(1, 6, visuals, List.of(), false));
        assertTrue(unchanged.size() > 1);
        List<EntitySnapshot> deltas = new ArrayList<EntitySnapshot>();
        for (ClientViewMessage.EntityFrame piece : unchanged) {
            assertFalse(piece.presence());
            deltas.addAll(piece.entities());
        }
        assertEntities(visuals, deltas);

        ClientViewMessage.EntityFrame small = new ClientViewMessage.EntityFrame(1, 1, visuals.subList(0, 2), List.of(), true);
        assertEquals(1, splitter.split(List.of(small), () -> 0).size());
    }

    private static List<ClientViewMessage.EntityFrame> entityPieces(FrameSplitter splitter, ClientViewMessage.EntityFrame frame)
        throws ViewStreamProtocolException {
        int[] next = {0};
        List<byte[]> frames = splitter.split(List.of(frame), () -> next[0]++);
        List<ClientViewMessage.EntityFrame> pieces = new ArrayList<ClientViewMessage.EntityFrame>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            byte[] encoded = frames.get(i);
            assertTrue(encoded.length <= splitter.maxFrameBytes(), "frame " + i + " is " + encoded.length + " bytes");
            ClientViewCodec.S2CFrame decoded = ClientViewCodec.decodeS2C(encoded, ViewStreamCapability.NONE);
            assertEquals(i == frames.size() - 1, decoded.last(), "frame " + i);
            pieces.add((ClientViewMessage.EntityFrame) decoded.message());
        }
        return pieces;
    }

    private static void assertEntities(List<EntitySnapshot> expected, List<EntitySnapshot> actual) throws ViewStreamProtocolException {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(ClientViewCodec.entityBytes(expected.get(i)), ClientViewCodec.entityBytes(actual.get(i)), "entity " + i);
        }
    }
}
