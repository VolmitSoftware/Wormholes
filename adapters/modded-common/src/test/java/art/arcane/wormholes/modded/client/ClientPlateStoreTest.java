package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.BrickCodec;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.PlateSectionBox;
import art.arcane.optics.math.BlockBox;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import art.arcane.wormholes.network.client.ClientViewExtensions;

public class ClientPlateStoreTest extends MinecraftTestBase {
    private static final Path GOLDENS = goldens();
    private static final long GENEROUS_BUDGET = 64L * 1024L * 1024L;

    @Test
    public void beginBricksEndCommitsAPlate() throws ViewStreamProtocolException {
        ClientPlateStore store = new ClientPlateStore(new ClientPalette(BuiltInRegistries.BLOCK), GENEROUS_BUDGET);
        PlateSectionBox sections = new PlateSectionBox(0, 4, 0, 1, 1, 1);
        BlockBox cells = new BlockBox(0, 64, 0, 16, 16, 16);
        ViewStreamMessage.PlateBegin begin = new ViewStreamMessage.PlateBegin(7, 1, sections, cells, 3, 1, null);
        begin = (ViewStreamMessage.PlateBegin) ClientViewExtensions.CODEC.decodeS2C(
            ClientViewExtensions.CODEC.encodeS2C(begin, 1, 0), ViewStreamCapability.ALL).message();
        assertNull(store.begin(begin));
        assertTrue(store.pending(7));
        assertEquals(1, store.bricks(new ViewStreamMessage.PlateBricks(7, 1, List.of(brick(0, 3, 5)))));
        ClientPlate plate = store.end(new ViewStreamMessage.PlateEnd(7, 1));
        assertNotNull(plate);
        assertFalse(store.pending(7));
        assertEquals(plate, store.plate(7));
        assertEquals(3, plate.paletteIdAt(0, 64, 0));
        assertEquals(5, plate.paletteIdAt(1, 64, 0));
        assertEquals(3, plate.paletteIdAt(15, 79, 15));
        assertEquals(ViewStreamLimits.PALETTE_AIR, plate.paletteIdAt(16, 64, 0));
        assertEquals(3, plate.backingState());
        assertTrue(store.bytes() > 0L);
        assertTrue(store.bytes() <= store.budgetBytes());
    }

    @Test
    public void staleBricksAndEndsAreIgnored() throws ViewStreamProtocolException {
        ClientPlateStore store = new ClientPlateStore(new ClientPalette(BuiltInRegistries.BLOCK), GENEROUS_BUDGET);
        store.begin(new ViewStreamMessage.PlateBegin(7, 2, new PlateSectionBox(0, 0, 0, 1, 1, 1), new BlockBox(0, 0, 0, 16, 16, 16), 3, 1, null));
        assertEquals(0, store.bricks(new ViewStreamMessage.PlateBricks(7, 1, List.of(brick(0, 3, 3)))));
        assertNull(store.end(new ViewStreamMessage.PlateEnd(7, 1)));
        assertNull(store.end(new ViewStreamMessage.PlateEnd(8, 2)));
        assertEquals(3, store.staleMessages());
        assertTrue(store.pending(7));
    }

    @Test
    public void brickMissOnlyAsksForHashesTheCacheDoesNotHold() throws ViewStreamProtocolException {
        ClientPlateStore store = new ClientPlateStore(new ClientPalette(BuiltInRegistries.BLOCK), GENEROUS_BUDGET);
        PlateSectionBox sections = new PlateSectionBox(0, 0, 0, 2, 1, 1);
        BlockBox cells = new BlockBox(0, 0, 0, 32, 16, 16);
        long[] hashes = {0x1111L, 0x2222L};
        ViewStreamMessage.BrickMiss.Plate first = store.begin(new ViewStreamMessage.PlateBegin(1, 1, sections, cells, 3, 2, hashes));
        assertNotNull(first);
        assertTrue(first.missed(0));
        assertTrue(first.missed(1));
        store.bricks(new ViewStreamMessage.PlateBricks(1, 1, List.of(brick(0, 3, 4), brick(1, 5, 6))));
        assertNotNull(store.end(new ViewStreamMessage.PlateEnd(1, 1)));
        assertEquals(2, store.cachedBricks());
        ViewStreamMessage.BrickMiss.Plate second = store.begin(new ViewStreamMessage.PlateBegin(2, 9, sections, cells, 3, 2, new long[] {0x2222L, 0x9999L}));
        assertNotNull(second);
        assertFalse(second.missed(0));
        assertTrue(second.missed(1));
        store.bricks(new ViewStreamMessage.PlateBricks(2, 9, List.of(brick(1, 7, 8))));
        ClientPlate second9 = store.end(new ViewStreamMessage.PlateEnd(2, 9));
        assertNotNull(second9);
        assertEquals(5, second9.paletteIdAt(0, 0, 0));
        assertEquals(6, second9.paletteIdAt(1, 0, 0));
        assertEquals(7, second9.paletteIdAt(16, 0, 0));
        assertEquals(1, store.cacheHits());
        assertEquals(3, store.cacheMisses());
    }

    @Test
    public void patchesRewriteBricksAndRejectRevisionGaps() throws ViewStreamProtocolException {
        ClientPlateStore store = new ClientPlateStore(new ClientPalette(BuiltInRegistries.BLOCK), GENEROUS_BUDGET);
        PlateSectionBox sections = new PlateSectionBox(0, 0, 0, 2, 1, 1);
        BlockBox cells = new BlockBox(0, 0, 0, 32, 16, 16);
        store.begin(new ViewStreamMessage.PlateBegin(3, 1, sections, cells, 3, 2, null));
        store.bricks(new ViewStreamMessage.PlateBricks(3, 1, List.of(brick(0, 3, 3), brick(1, 4, 4))));
        assertNotNull(store.end(new ViewStreamMessage.PlateEnd(3, 1)));
        IntArrayList touched = new IntArrayList();
        ViewStreamMessage.PlatePatch patch = new ViewStreamMessage.PlatePatch(3, 1, 2, List.of(
            new ViewStreamMessage.SparseOp(0, new int[] {ViewStreamLimits.brickCellIndex(2, 3, 4)}, new int[] {9}),
            new ViewStreamMessage.ClearOp(1)));
        ClientPlate patched = store.patch(patch, touched);
        assertNotNull(patched);
        assertEquals(2, patched.revision());
        assertEquals(9, patched.paletteIdAt(2, 3, 4));
        assertEquals(3, patched.paletteIdAt(0, 0, 0));
        assertEquals(ViewStreamLimits.PALETTE_AIR, patched.paletteIdAt(16, 0, 0));
        assertEquals(List.of(0, 1), touched);
        assertNull(store.patch(new ViewStreamMessage.PlatePatch(3, 1, 3, List.of(new ViewStreamMessage.ClearOp(0))), new IntArrayList()));
        ClientPlate full = store.patch(new ViewStreamMessage.PlatePatch(3, 2, 3, List.of(new ViewStreamMessage.FullOp(brick(1, 6, 6)))), new IntArrayList());
        assertNotNull(full);
        assertEquals(6, full.paletteIdAt(17, 0, 0));
        assertEquals(3, store.plate(3).revision());
    }

    @Test
    public void plateMemoryNeverExceedsTheBudget() throws ViewStreamProtocolException {
        long budget = 96L * 1024L;
        ClientPlateStore store = new ClientPlateStore(new ClientPalette(BuiltInRegistries.BLOCK), budget);
        PlateSectionBox big = new PlateSectionBox(0, 0, 0, 4, 4, 4);
        BlockBox bigCells = new BlockBox(0, 0, 0, 64, 64, 64);
        store.begin(new ViewStreamMessage.PlateBegin(1, 1, big, bigCells, 3, 64, null));
        Brick[] noisy = new Brick[64];
        for (int index = 0; index < noisy.length; index++) {
            noisy[index] = noisyBrick(index);
        }
        store.bricks(new ViewStreamMessage.PlateBricks(1, 1, List.of(noisy)));
        assertNull(store.end(new ViewStreamMessage.PlateEnd(1, 1)));
        assertEquals(1, store.refusedPlates());
        assertEquals(0, store.size());
        assertTrue(store.bytes() <= budget);
        PlateSectionBox small = new PlateSectionBox(0, 0, 0, 1, 1, 1);
        store.begin(new ViewStreamMessage.PlateBegin(2, 1, small, new BlockBox(0, 0, 0, 16, 16, 16), 3, 1, new long[] {42L}));
        store.bricks(new ViewStreamMessage.PlateBricks(2, 1, List.of(noisyBrick(0))));
        assertNotNull(store.end(new ViewStreamMessage.PlateEnd(2, 1)));
        assertTrue(store.bytes() <= budget);
        for (int round = 0; round < 40; round++) {
            long hash = 1000L + round;
            store.begin(new ViewStreamMessage.PlateBegin(2, 2 + round, small, new BlockBox(0, 0, 0, 16, 16, 16), 3, 1, new long[] {hash}));
            store.bricks(new ViewStreamMessage.PlateBricks(2, 2 + round, List.of(noisyBrick(0, round))));
            assertNotNull(store.end(new ViewStreamMessage.PlateEnd(2, 2 + round)));
            assertTrue("round " + round + " bytes " + store.bytes(), store.bytes() <= budget);
        }
        assertTrue(store.cachedBricks() > 0);
        store.drop(2);
        assertEquals(0L, store.plateBytes());
        assertTrue(store.bytes() <= budget);
    }

    @Test
    public void aPlateOverTheBudgetIsRefusedAndReportedOnce() throws ViewStreamProtocolException {
        ClientPlateStore store = new ClientPlateStore(new ClientPalette(BuiltInRegistries.BLOCK), 1024L * 1024L);
        PlateSectionBox sections = new PlateSectionBox(0, 0, 0, 16, 16, 16);
        BlockBox cells = new BlockBox(0, 0, 0, 256, 256, 256);
        store.begin(new ViewStreamMessage.PlateBegin(4, 2, sections, cells, 3, sections.brickCount(), null));
        assertNull(store.end(new ViewStreamMessage.PlateEnd(4, 2)));
        assertEquals(1, store.refusedPlates());
        assertEquals(new ViewStreamMessage.PlateRefused(4, 2), store.takeRefusal());
        assertNull(store.takeRefusal());
        assertTrue(store.bytes() <= store.budgetBytes());
    }

    @Test
    public void aPlateBoxTheSweepCannotHoldIsRefusedAtBegin() throws ViewStreamProtocolException {
        ClientPlateStore store = new ClientPlateStore(new ClientPalette(BuiltInRegistries.BLOCK), GENEROUS_BUDGET);
        BlockBox cells = new BlockBox(0, 0, 0, 300, 200, 300);
        PlateSectionBox sections = PlateSectionBox.snap(cells);
        long[] hashes = new long[sections.brickCount()];
        assertNull(store.begin(new ViewStreamMessage.PlateBegin(5, 1, sections, cells, 3, sections.brickCount(), hashes)));
        assertFalse(store.pending(5));
        assertEquals(new ViewStreamMessage.PlateRefused(5, 1), store.takeRefusal());
        assertEquals(0, store.bricks(new ViewStreamMessage.PlateBricks(5, 1, List.of(brick(0, 3, 3)))));
        assertNull(store.end(new ViewStreamMessage.PlateEnd(5, 1)));
        assertEquals(1, store.refusedPlates());
    }

    @Test
    public void inFlightBricksCountAgainstTheBudget() throws ViewStreamProtocolException {
        long budget = 256L * 1024L;
        ClientPlateStore store = new ClientPlateStore(new ClientPalette(BuiltInRegistries.BLOCK), budget);
        PlateSectionBox big = new PlateSectionBox(0, 0, 0, 4, 4, 4);
        store.begin(new ViewStreamMessage.PlateBegin(1, 1, big, new BlockBox(0, 0, 0, 64, 64, 64), 3, 64, null));
        int accepted = 0;
        for (int index = 0; index < 64 && store.pending(1); index++) {
            accepted += store.bricks(new ViewStreamMessage.PlateBricks(1, 1, List.of(noisyBrick(index))));
            assertTrue("brick " + index + " bytes " + store.bytes(), store.bytes() <= budget);
        }
        assertFalse(store.pending(1));
        assertTrue("accepted " + accepted, accepted > 0 && accepted < 64);
        assertEquals(new ViewStreamMessage.PlateRefused(1, 1), store.takeRefusal());
        assertEquals(0, store.bricks(new ViewStreamMessage.PlateBricks(1, 1, List.of(noisyBrick(63)))));
        assertNull(store.end(new ViewStreamMessage.PlateEnd(1, 1)));
        assertEquals(1, store.refusedPlates());
        assertEquals(0L, store.bytes());
    }

    @Test
    public void goldenPlateVectorsDecodeIntoTheStore() throws IOException, ViewStreamProtocolException {
        ViewStreamMessage.PlateBegin goldenBegin = (ViewStreamMessage.PlateBegin) golden("plate_begin_plain");
        ViewStreamMessage.PlateBricks goldenBricks = (ViewStreamMessage.PlateBricks) golden("plate_bricks");
        ViewStreamMessage.PlateEnd goldenEnd = (ViewStreamMessage.PlateEnd) golden("plate_end");
        ViewStreamMessage.PlateBegin hashed = (ViewStreamMessage.PlateBegin) golden("plate_begin_hashes");
        assertTrue(hashed.hasHashes());
        assertFalse(goldenBegin.hasHashes());
        assertEquals(goldenBegin.sections().brickCount(), goldenBegin.brickCount());
        int portalKey = goldenBricks.portalKey();
        int revision = goldenBricks.plateRevision();
        ViewStreamMessage.PlateBegin begin = new ViewStreamMessage.PlateBegin(portalKey, revision, goldenBegin.sections(), goldenBegin.cells(),
            goldenBegin.backingState(), goldenBegin.brickCount(), null);
        ClientPlateStore store = new ClientPlateStore(new ClientPalette(BuiltInRegistries.BLOCK), GENEROUS_BUDGET);
        assertNull(store.begin(begin));
        assertEquals(goldenBricks.bricks().size(), store.bricks(goldenBricks));
        ViewStreamMessage.PlateEnd end = goldenEnd.portalKey() == portalKey && goldenEnd.plateRevision() == revision
            ? goldenEnd
            : new ViewStreamMessage.PlateEnd(portalKey, revision);
        ClientPlate plate = store.end(end);
        assertNotNull(plate);
        PlateSectionBox sections = plate.sections();
        int checked = 0;
        for (Brick brick : goldenBricks.bricks()) {
            int baseX = sections.sectionX(brick.brickIndex()) << 4;
            int baseY = sections.sectionY(brick.brickIndex()) << 4;
            int baseZ = sections.sectionZ(brick.brickIndex()) << 4;
            for (int cellIndex = 0; cellIndex < ViewStreamLimits.BRICK_CELLS; cellIndex += 97) {
                int x = baseX + ViewStreamLimits.brickCellX(cellIndex);
                int y = baseY + ViewStreamLimits.brickCellY(cellIndex);
                int z = baseZ + ViewStreamLimits.brickCellZ(cellIndex);
                int expected = plate.contains(x, y, z) ? brick.paletteIdAt(cellIndex) : ViewStreamLimits.PALETTE_AIR;
                assertEquals("cell " + x + "," + y + "," + z, expected, plate.paletteIdAt(x, y, z));
                checked++;
            }
            if (brick.hasLight()) {
                assertTrue(plate.hasLight(brick.brickIndex()));
                assertEquals(ViewStreamLimits.LIGHT_NIBBLE_BYTES, plate.blockLight(brick.brickIndex()).length);
            }
        }
        assertTrue(checked > 0);
        assertEquals(plate.bytes(), store.plateBytes());
    }

    private static Path goldens() {
        Path relative = Path.of("optics", "src", "test", "resources", "art", "arcane", "optics", "stream", "goldens");
        Path cursor = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 8 && cursor != null; depth++) {
            Path candidate = cursor.resolve(relative);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("ClientView golden vectors not found above " + Path.of("").toAbsolutePath());
    }

    private static ViewStreamMessage golden(String name) throws IOException, ViewStreamProtocolException {
        long caps = ViewStreamCapability.ALL;
        for (String line : Files.readAllLines(GOLDENS.resolve("vectors.txt"), StandardCharsets.UTF_8)) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 3 && parts[0].equals(name)) {
                caps = Long.parseLong(parts[2], 16);
            }
        }
        String hex = Files.readString(GOLDENS.resolve(name + ".hex"), StandardCharsets.UTF_8).trim();
        return ClientViewExtensions.CODEC.decodeS2C(HexFormat.of().parseHex(hex), caps).message();
    }

    private static Brick brick(int brickIndex, int fillId, int firstCellId) {
        int[] cells = new int[ViewStreamLimits.BRICK_CELLS];
        Arrays.fill(cells, fillId);
        cells[ViewStreamLimits.brickCellIndex(1, 0, 0)] = firstCellId;
        return BrickCodec.pack(brickIndex, cells);
    }

    private static Brick noisyBrick(int brickIndex) {
        return noisyBrick(brickIndex, brickIndex);
    }

    private static Brick noisyBrick(int brickIndex, int salt) {
        int[] cells = new int[ViewStreamLimits.BRICK_CELLS];
        for (int index = 0; index < cells.length; index++) {
            cells[index] = 3 + ((index * 31 + salt) % 200);
        }
        return BrickCodec.pack(brickIndex, cells);
    }
}
