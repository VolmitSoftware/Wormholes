package art.arcane.optics.stream;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MeshReuseHashTest {
    @Test
    void packedIndexReusePreservesExpandedCanonicalHashAtEverySupportedWidth() throws ViewStreamProtocolException {
        IntFunction<String> names = id -> "test:block_" + id;
        for (int size : new int[] {2, 3, 5, 17, 257}) {
            for (boolean air : new boolean[] {false, true}) {
                int[] cells = new int[ViewStreamLimits.BRICK_CELLS];
                for (int cell = 0; cell < cells.length; cell++) {
                    cells[cell] = air && cell % size == 0 ? 0 : 3 + cell % size;
                }
                byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
                byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
                Arrays.fill(block, (byte) 0x74);
                Arrays.fill(sky, (byte) 0x9a);
                byte[] originalBlock = block.clone();
                byte[] originalSky = sky.clone();
                Brick.BlockEntityCell[] entities = {new Brick.BlockEntityCell(91, new byte[] {2, 3}),
                    new Brick.BlockEntityCell(13, new byte[] {7, 8})};
                Brick brick = BrickCodec.pack(0, cells).withLight(block, sky).withBlockEntities(entities);
                int[] originalPalette = brick.localPalette().clone();
                long[] originalIndices = brick.packedIndices().clone();
                byte[] biomeIndices = new byte[SectionBiomes.INDEX_BYTES];
                for (int cell = 0; cell < SectionBiomes.CELLS; cell++) {
                    biomeIndices[cell * 2] = (byte) (cell % 3);
                }
                SectionBiomes biomes = new SectionBiomes(List.of("minecraft:forest", "minecraft:plains", "minecraft:forest"), biomeIndices);
                ViewStreamMessage.MeshSection section = section(brick, biomes);
                assertEquals(expandedHash(section, 71, names), MeshHash.resolved(section, 71, names));
                assertArrayEquals(originalPalette, brick.localPalette());
                assertArrayEquals(originalIndices, brick.packedIndices());
                assertArrayEquals(cells, unpack(brick));
                assertArrayEquals(entities, brick.blockEntities());
                assertArrayEquals(originalBlock, block);
                assertArrayEquals(originalSky, sky);
                assertArrayEquals(biomeIndices, biomes.indices());
            }
        }
    }

    @Test
    void aliasesUnusedReorderedAndOverwidePalettesPreserveExpandedCanonicalHashes() throws ViewStreamProtocolException {
        IntFunction<String> names = Map.of(7, "minecraft:stone", 9, "stone", 8, "minecraft:dirt", 11, "oak_log[axis=y]")::get;
        for (Brick brick : List.of(indexedBrick(2, new int[] {7, 9, 8, 11}, new int[] {0, 1, 2, 3}),
            indexedBrick(2, new int[] {7, 8, 11}, new int[] {0, 1}),
            indexedBrick(2, new int[] {7, 8, 11}, new int[] {2, 0, 1}),
            indexedBrick(16, new int[] {7, 8}, new int[] {0, 1}),
            indexedBrick(2, new int[] {ViewStreamLimits.PALETTE_BACKING, ViewStreamLimits.PALETTE_OCCLUDED, 7, 8},
                new int[] {0, 1, 2, 3}))) {
            ViewStreamMessage.MeshSection section = section(brick, SectionBiomes.NONE);
            assertEquals(expandedHash(section, 71, names), MeshHash.resolved(section, 71, names));
        }
    }

    @Test
    void canonicalLayoutSharesPackedWordsWhileReorderedLayoutKeepsCanonicalRepacking() throws Exception {
        IntFunction<String> names = Map.of(7, "minecraft:stone", 8, "minecraft:dirt", 11, "minecraft:oak_log[axis=y]")::get;
        Brick ordered = indexedBrick(2, new int[] {7, 8, 11}, new int[] {0, 1, 2});
        Brick reordered = indexedBrick(2, new int[] {7, 8, 11}, new int[] {2, 0, 1});
        assertSame(ordered.packedIndices(), canonicalBrick(ordered, names).packedIndices());
        assertNotSame(reordered.packedIndices(), canonicalBrick(reordered, names).packedIndices());
    }

    @Test
    void uniformSectionsPreserveCanonicalDigestsWithAndWithoutExtras() throws ViewStreamProtocolException {
        IntFunction<String> names = Map.of(7, "minecraft:stone", 9, "minecraft:stone", 81, "custom:block")::get;
        assertEquals(3797941112907736624L, MeshHash.resolved(section(Brick.empty(0), SectionBiomes.NONE), 71, names));
        assertEquals(-586368193290271323L, MeshHash.resolved(section(Brick.single(0, 7), SectionBiomes.NONE), 71, names));
        assertEquals(-586368193290271323L, MeshHash.resolved(section(Brick.single(0, ViewStreamLimits.PALETTE_BACKING),
            SectionBiomes.NONE), 71, names));
        assertEquals(-1765332103534220278L, MeshHash.resolved(section(Brick.single(0, 81), SectionBiomes.NONE), 71, names));
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        Arrays.fill(block, (byte) 0x73);
        Arrays.fill(sky, (byte) 0xff);
        Brick brick = Brick.single(0, 7).withLight(block, sky).withBlockEntities(new Brick.BlockEntityCell[] {
            new Brick.BlockEntityCell(12, new byte[] {1, 2, 3}), new Brick.BlockEntityCell(4, new byte[] {4, 5})});
        assertEquals(5406074095276224041L, MeshHash.resolved(section(brick,
            new SectionBiomes(List.of("minecraft:plains"), new byte[0])), 71, names));
    }

    @Test
    void unusedPaletteEntriesAreIgnoredAndDuplicateIdsResolveOnce() throws ViewStreamProtocolException {
        long[] packed = new long[Brick.packedLongs(4)];
        packed[0] = 0x1111111111111234L;
        Arrays.fill(packed, 1, packed.length, 0x1111111111111111L);
        Brick brick = new Brick(0, Brick.Encoding.PALETTED, 4, 0, 0,
            new int[] {100, 7, 9, 7, 81}, packed, null, null, null);
        Map<Integer, Integer> calls = new HashMap<>();
        IntFunction<String> names = id -> {
            calls.merge(id, 1, Integer::sum);
            return switch (id) {
                case 7 -> "minecraft:stone";
                case 9 -> "stone";
                case 81 -> "oak_log[axis=y]";
                default -> throw new AssertionError("Unused palette state was resolved: " + id);
            };
        };
        int[] cells = new int[ViewStreamLimits.BRICK_CELLS];
        Arrays.fill(cells, 7);
        cells[0] = 81;
        assertEquals(MeshHash.resolved(section(BrickCodec.pack(0, cells), SectionBiomes.NONE), 71,
            Map.of(7, "minecraft:stone", 81, "minecraft:oak_log[axis=y]")::get),
            MeshHash.resolved(section(brick, SectionBiomes.NONE), 71, names));
        assertEquals(Map.of(7, 1, 9, 1, 81, 1), calls);
    }

    @Test
    void usedPaletteAliasesAndSentinelsCollapseToTheSameUniformContent() throws ViewStreamProtocolException {
        long[] packed = new long[Brick.packedLongs(2)];
        Arrays.fill(packed, 0xe4e4e4e4e4e4e4e4L);
        Brick brick = new Brick(0, Brick.Encoding.PALETTED, 2, 0, 0,
            new int[] {ViewStreamLimits.PALETTE_BACKING, ViewStreamLimits.PALETTE_OCCLUDED, 9, 81},
            packed, null, null, null);
        IntFunction<String> names = Map.of(7, "minecraft:stone", 9, "stone", 81, "minecraft:stone")::get;
        assertEquals(MeshHash.resolved(section(Brick.single(0, 7), SectionBiomes.NONE), 71, names),
            MeshHash.resolved(section(brick, SectionBiomes.NONE), 71, names));
        assertEquals(MeshHash.resolved(section(Brick.empty(0), SectionBiomes.NONE), 71, names),
            MeshHash.resolved(section(brick, SectionBiomes.NONE), 71, id -> "minecraft:air"));
    }

    @Test
    void invalidUsedLocalIndexStillFails() {
        long[] packed = new long[Brick.packedLongs(2)];
        packed[0] = 3;
        Brick brick = new Brick(0, Brick.Encoding.PALETTED, 2, 0, 0,
            new int[] {7, 8, 9}, packed, null, null, null);
        assertThrows(ArrayIndexOutOfBoundsException.class,
            () -> MeshHash.resolved(section(brick, SectionBiomes.NONE), 71, id -> "minecraft:stone"));
    }

    @Test
    void resolvedHashIgnoresUnusedBackingButIncludesResolvedSentinelCells() throws ViewStreamProtocolException {
        IntFunction<String> names = Map.of(7, "minecraft:stone", 8, "minecraft:dirt", 9, "minecraft:stone")::get;
        ViewStreamMessage.MeshSection previous = section(Brick.single(0, 7), SectionBiomes.NONE);
        ViewStreamMessage.MeshSection changedBacking = new ViewStreamMessage.MeshSection(1, 1, 2, -3, 4, 1, 8,
            previous.brick(), previous.biomes());
        assertEquals(MeshHash.resolved(previous, 71, names), MeshHash.resolved(changedBacking, 71, names));
        for (int sentinel : List.of(ViewStreamLimits.PALETTE_BACKING, ViewStreamLimits.PALETTE_OCCLUDED)) {
            ViewStreamMessage.MeshSection stone = section(Brick.single(0, sentinel), SectionBiomes.NONE);
            ViewStreamMessage.MeshSection dirt = new ViewStreamMessage.MeshSection(1, 1, 2, -3, 4, 1, 8,
                stone.brick(), stone.biomes());
            assertNotEquals(MeshHash.resolved(stone, 71, names), MeshHash.resolved(dirt, 71, names));
        }
    }

    @Test
    void resolvedContentSurvivesPaletteIdsPackingOrderAndTransportChanges() throws ViewStreamProtocolException {
        int[] cells = new int[ViewStreamLimits.BRICK_CELLS];
        cells[0] = 7;
        cells[1] = 8;
        Brick first = BrickCodec.pack(0, cells);
        int[] nextCells = cells.clone();
        nextCells[0] = 81;
        nextCells[1] = 32;
        IntFunction<String> firstNames = Map.of(7, "oak_log[axis=y]", 8, "minecraft:stone", 9, "minecraft:stone")::get;
        IntFunction<String> nextNames = Map.of(81, "minecraft:oak_log[axis=y]", 32, "stone", 95, "stone")::get;
        ViewStreamMessage.MeshSection previous = section(first, SectionBiomes.NONE);
        ViewStreamMessage.MeshSection next = new ViewStreamMessage.MeshSection(99, 5, 2, -3, 4, 19, 95,
            BrickCodec.pack(0, nextCells), SectionBiomes.NONE);
        assertEquals(MeshHash.resolved(previous, 71, firstNames), MeshHash.resolved(next, 71, nextNames));
        assertArrayEquals(cells, unpack(first));
        assertArrayEquals(nextCells, unpack(next.brick()));
    }

    @Test
    void resolvedContentIncludesRealStatesAndConnectionEpoch() throws ViewStreamProtocolException {
        ViewStreamMessage.MeshSection section = section(Brick.single(0, 7), SectionBiomes.NONE);
        IntFunction<String> names = Map.of(7, "minecraft:grass_block[snowy=false]", 9, "minecraft:stone")::get;
        IntFunction<String> changed = Map.of(7, "minecraft:grass_block[snowy=true]", 9, "minecraft:stone")::get;
        long hash = MeshHash.resolved(section, 71, names);
        assertNotEquals(hash, MeshHash.resolved(section, 71, changed));
        assertNotEquals(hash, MeshHash.resolved(section, 72, names));
        assertThrows(ViewStreamProtocolException.class, () -> MeshHash.resolved(section, 71, id -> null));
    }

    @Test
    void resolvedBlockEntityAndBiomeOrderDoesNotMutateCachedSource() throws ViewStreamProtocolException {
        Brick.BlockEntityCell firstEntity = new Brick.BlockEntityCell(12, new byte[] {1, 2, 3});
        Brick.BlockEntityCell secondEntity = new Brick.BlockEntityCell(4, new byte[] {4, 5, 6});
        Brick.BlockEntityCell[] entities = {firstEntity, secondEntity};
        Brick first = Brick.single(0, 7).withBlockEntities(entities);
        Brick next = Brick.single(0, 81).withBlockEntities(new Brick.BlockEntityCell[] {secondEntity, firstEntity});
        byte[] firstIndices = new byte[SectionBiomes.INDEX_BYTES];
        byte[] nextIndices = new byte[SectionBiomes.INDEX_BYTES];
        for (int cell = 0; cell < SectionBiomes.CELLS; cell++) {
            firstIndices[cell * 2] = (byte) (cell % 2);
            nextIndices[cell * 2] = (byte) (1 - cell % 2);
        }
        SectionBiomes firstBiomes = new SectionBiomes(List.of("minecraft:plains", "minecraft:forest"), firstIndices);
        SectionBiomes nextBiomes = new SectionBiomes(List.of("minecraft:forest", "minecraft:plains"), nextIndices);
        IntFunction<String> names = Map.of(7, "minecraft:stone", 81, "minecraft:stone", 9, "minecraft:stone")::get;
        assertEquals(MeshHash.resolved(section(first, firstBiomes), 71, names),
            MeshHash.resolved(section(next, nextBiomes), 71, names));
        assertArrayEquals(new Brick.BlockEntityCell[] {firstEntity, secondEntity}, first.blockEntities());
        assertArrayEquals(firstIndices, firstBiomes.indices());
    }

    @Test
    void transportIdentityDoesNotInvalidateIdenticalSectionContent() throws ViewStreamProtocolException {
        ViewStreamMessage.MeshSection first = section(Brick.single(0, 7), SectionBiomes.NONE);
        ViewStreamMessage.MeshSection next = new ViewStreamMessage.MeshSection(99, 52, first.sectionX(), first.sectionY(),
            first.sectionZ(), 108, first.backingState(), first.brick(), first.biomes());
        assertEquals(MeshHash.hash(first, 31), MeshHash.hash(next, 31));
    }

    @Test
    void coordinatesBackingAndDictionaryIdentityCannotReuseAnotherSection() throws ViewStreamProtocolException {
        ViewStreamMessage.MeshSection first = section(Brick.single(0, 7), SectionBiomes.NONE);
        long hash = MeshHash.hash(first, 31);
        assertNotEquals(hash, MeshHash.hash(first, 32));
        assertNotEquals(hash, MeshHash.hash(new ViewStreamMessage.MeshSection(1, 1, 2, -3, 4, 1, 8,
            first.brick(), first.biomes()), 31));
        assertNotEquals(hash, MeshHash.hash(new ViewStreamMessage.MeshSection(1, 1, 3, -3, 4, 1, 9,
            first.brick(), first.biomes()), 31));
        assertNotEquals(hash, MeshHash.hash(new ViewStreamMessage.MeshSection(1, 1, 2, -2, 4, 1, 9,
            first.brick(), first.biomes()), 31));
        assertNotEquals(hash, MeshHash.hash(new ViewStreamMessage.MeshSection(1, 1, 2, -3, 5, 1, 9,
            first.brick(), first.biomes()), 31));
    }

    @Test
    void blockLightSkyLightAndBlockEntityUpdatesInvalidateClaims() throws ViewStreamProtocolException {
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        Brick first = Brick.single(0, 7).withLight(block, sky)
            .withBlockEntities(new Brick.BlockEntityCell[] {new Brick.BlockEntityCell(12, new byte[] {1, 2, 3})});
        long hash = MeshHash.hash(section(first, SectionBiomes.NONE), 31);
        assertNotEquals(hash, MeshHash.hash(section(Brick.single(0, 8).withLight(block, sky)
            .withBlockEntities(first.blockEntities()), SectionBiomes.NONE), 31));
        byte[] changedBlock = block.clone();
        changedBlock[17] = 3;
        assertNotEquals(hash, MeshHash.hash(section(first.withLight(changedBlock, sky), SectionBiomes.NONE), 31));
        byte[] changedSky = sky.clone();
        changedSky[19] = 4;
        assertNotEquals(hash, MeshHash.hash(section(first.withLight(block, changedSky), SectionBiomes.NONE), 31));
        assertNotEquals(hash, MeshHash.hash(section(first.withBlockEntities(new Brick.BlockEntityCell[] {
            new Brick.BlockEntityCell(12, new byte[] {1, 2, 4})}), SectionBiomes.NONE), 31));
        assertNotEquals(hash, MeshHash.hash(section(first.withBlockEntities(new Brick.BlockEntityCell[] {
            new Brick.BlockEntityCell(13, new byte[] {1, 2, 3})}), SectionBiomes.NONE), 31));
        assertNotEquals(hash, MeshHash.hash(section(first.withBlockEntities(null), SectionBiomes.NONE), 31));
    }

    @Test
    void packedBlocksAndBiomePaletteOrCellChangesInvalidateClaims() throws ViewStreamProtocolException {
        int[] cells = new int[ViewStreamLimits.BRICK_CELLS];
        cells[12] = 7;
        Brick first = BrickCodec.pack(0, cells);
        byte[] biomeCells = new byte[SectionBiomes.INDEX_BYTES];
        SectionBiomes biomes = new SectionBiomes(List.of("minecraft:plains", "minecraft:desert"), biomeCells);
        long hash = MeshHash.hash(section(first, biomes), 31);
        cells[13] = 7;
        assertNotEquals(hash, MeshHash.hash(section(BrickCodec.pack(0, cells), biomes), 31));
        byte[] changedBiomes = biomeCells.clone();
        changedBiomes[22] = 1;
        assertNotEquals(hash, MeshHash.hash(section(first,
            new SectionBiomes(biomes.palette(), changedBiomes)), 31));
        assertNotEquals(hash, MeshHash.hash(section(first,
            new SectionBiomes(List.of("minecraft:plains", "minecraft:forest"), biomeCells)), 31));
        assertNotEquals(hash, MeshHash.hash(section(first, SectionBiomes.NONE), 31));
    }

    private static ViewStreamMessage.MeshSection section(Brick brick, SectionBiomes biomes) {
        return new ViewStreamMessage.MeshSection(1, 1, 2, -3, 4, 1, 9, brick, biomes);
    }

    private static Brick indexedBrick(int bits, int[] palette, int[] indices) {
        long[] packed = new long[Brick.packedLongs(bits)];
        for (int cell = 0; cell < ViewStreamLimits.BRICK_CELLS; cell++) {
            int offset = cell * bits;
            packed[offset >>> 6] |= (long) indices[cell % indices.length] << (offset & 63);
        }
        return new Brick(0, Brick.Encoding.PALETTED, bits, 0, 0, palette, packed, null, null, null);
    }

    private static Brick canonicalBrick(Brick original, IntFunction<String> names) throws Exception {
        TreeMap<String, Integer> dictionary = new TreeMap<>();
        dictionary.put(SessionPalette.AIR, 0);
        Method resolve = MeshHash.class.getDeclaredMethod("palette", Brick.class, int.class, IntFunction.class, TreeMap.class);
        resolve.setAccessible(true);
        Object palette = resolve.invoke(null, original, 9, names, dictionary);
        int id = 0;
        for (Map.Entry<String, Integer> entry : dictionary.entrySet()) {
            entry.setValue(id++);
        }
        Method pack = MeshHash.class.getDeclaredMethod("pack", Brick.class, palette.getClass(), TreeMap.class);
        pack.setAccessible(true);
        return (Brick) pack.invoke(null, original, palette, dictionary);
    }

    private static long expandedHash(ViewStreamMessage.MeshSection section, long epoch, IntFunction<String> names)
        throws ViewStreamProtocolException {
        TreeMap<String, Integer> dictionary = new TreeMap<>();
        dictionary.put(SessionPalette.AIR, 0);
        String[] states = new String[ViewStreamLimits.BRICK_CELLS];
        for (int cell = 0; cell < states.length; cell++) {
            int id = section.brick().paletteIdAt(cell);
            if (id == ViewStreamLimits.PALETTE_BACKING || id == ViewStreamLimits.PALETTE_OCCLUDED) {
                id = section.backingState();
            }
            states[cell] = id == ViewStreamLimits.PALETTE_AIR ? SessionPalette.AIR : SessionPalette.canonical(names.apply(id));
            dictionary.put(states[cell], 0);
        }
        ViewStreamWriter output = new ViewStreamWriter(1024);
        output.varint(dictionary.size());
        int id = 0;
        for (Map.Entry<String, Integer> entry : dictionary.entrySet()) {
            entry.setValue(id++);
            output.string(entry.getKey());
        }
        int[] cells = new int[states.length];
        for (int cell = 0; cell < cells.length; cell++) {
            cells[cell] = dictionary.get(states[cell]);
        }
        Brick.BlockEntityCell[] entities = section.brick().blockEntities().clone();
        Arrays.sort(entities, Comparator.comparingInt(Brick.BlockEntityCell::cellIndex));
        Brick brick = BrickCodec.pack(0, cells).withLight(section.brick().blockLight(), section.brick().skyLight())
            .withBlockEntities(entities);
        TreeMap<String, Integer> biomeIds = new TreeMap<>();
        for (int cell = 0; cell < SectionBiomes.CELLS && section.biomes().palette().size() > 1; cell++) {
            biomeIds.put(section.biomes().biome(cell), 0);
        }
        int biomeId = 0;
        for (Map.Entry<String, Integer> entry : biomeIds.entrySet()) {
            entry.setValue(biomeId++);
        }
        byte[] biomeIndices = biomeIds.size() <= 1 ? new byte[0] : new byte[SectionBiomes.INDEX_BYTES];
        for (int cell = 0; cell < SectionBiomes.CELLS && biomeIndices.length > 0; cell++) {
            int index = biomeIds.get(section.biomes().biome(cell));
            biomeIndices[cell * 2] = (byte) index;
            biomeIndices[cell * 2 + 1] = (byte) (index >>> 8);
        }
        SectionBiomes biomes = section.biomes().palette().size() <= 1 ? section.biomes()
            : new SectionBiomes(List.copyOf(biomeIds.keySet()), biomeIndices);
        output.bytes(ViewStreamFixtures.CODEC.encodeBody(new ViewStreamMessage.MeshSection(0, 1, section.sectionX(), section.sectionY(),
            section.sectionZ(), 1, dictionary.get(SessionPalette.AIR), brick, biomes)));
        return XxHash64.hash(output.rawBuffer(), 0, output.size(), epoch);
    }

    private static int[] unpack(Brick brick) {
        int[] cells = new int[ViewStreamLimits.BRICK_CELLS];
        for (int cell = 0; cell < cells.length; cell++) {
            cells[cell] = brick.paletteIdAt(cell);
        }
        return cells;
    }
}
