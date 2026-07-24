package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.network.view.ViewSlice;
import art.arcane.wormholes.render.view.OccludedMarker;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkBulkBuilderVenticularTest {
    @Test
    void fullyEnclosedInteriorCellIsSubstitutedWithSentinelWhileShellStaysReal() {
        int size = 3;
        int cells = size * size * size;
        boolean[] occluding = new boolean[cells];
        Arrays.fill(occluding, true);
        short[] indices = new short[cells];
        List<String> palette = new ArrayList<>(List.of("minecraft:stone"));
        HashMap<String, Integer> lookup = new HashMap<>();
        lookup.put("minecraft:stone", 0);

        ChunkBulkBuilder.substituteBuriedCells(0, 0, 0, size, size, size, occluding, indices, palette, lookup);

        int centerCell = cellIndex(1, 1, 1, size, size);
        int sentinelIndex = palette.indexOf(OccludedMarker.STATE_STRING);
        assertTrue(sentinelIndex >= 0, "sentinel must be added to palette when a cell is buried");
        assertEquals(sentinelIndex, indices[centerCell], "the fully enclosed interior cell must become the sentinel");
        for (int cell = 0; cell < cells; cell++) {
            if (cell != centerCell) {
                assertEquals(0, indices[cell], "shell cell " + cell + " must stay its real block");
            }
        }
    }

    @Test
    void slabWithoutFullyEnclosedCellsAddsNoSentinelAndPreservesIndices() {
        int sizeX = 3;
        int sizeY = 3;
        int sizeZ = 1;
        int cells = sizeX * sizeY * sizeZ;
        boolean[] occluding = new boolean[cells];
        Arrays.fill(occluding, true);
        short[] indices = new short[cells];
        List<String> palette = new ArrayList<>(List.of("minecraft:stone"));
        HashMap<String, Integer> lookup = new HashMap<>();
        lookup.put("minecraft:stone", 0);

        ChunkBulkBuilder.substituteBuriedCells(0, 0, 0, sizeX, sizeY, sizeZ, occluding, indices, palette, lookup);

        assertEquals(1, palette.size(), "no buried cell means the sentinel must not be added");
        for (short index : indices) {
            assertEquals(0, index, "no cell may be rewritten when nothing is fully enclosed");
        }
    }

    @Test
    void venticularContentHashIsStableAcrossRebuildsAndDivergesFromPanopticOnlyWhenBuried() {
        long panopticFullySolid = panopticHash(3, 3, 3);
        long venticularFirst = venticularHash(3, 3, 3);
        long venticularSecond = venticularHash(3, 3, 3);
        assertEquals(venticularFirst, venticularSecond, "an identical venticular slice must hash identically on rebuild");
        assertNotEquals(panopticFullySolid, venticularFirst, "a buried interior must make venticular differ from panoptic");

        long panopticSlab = panopticHash(3, 3, 1);
        long venticularSlab = venticularHash(3, 3, 1);
        assertEquals(panopticSlab, venticularSlab, "with no buried cells venticular must hash identically to panoptic");
    }

    private static long panopticHash(int sizeX, int sizeY, int sizeZ) {
        int cells = sizeX * sizeY * sizeZ;
        short[] indices = new short[cells];
        return sliceHash(sizeX, sizeY, sizeZ, indices, new ArrayList<>(List.of("minecraft:stone")));
    }

    private static long venticularHash(int sizeX, int sizeY, int sizeZ) {
        int cells = sizeX * sizeY * sizeZ;
        boolean[] occluding = new boolean[cells];
        Arrays.fill(occluding, true);
        short[] indices = new short[cells];
        List<String> palette = new ArrayList<>(List.of("minecraft:stone"));
        HashMap<String, Integer> lookup = new HashMap<>();
        lookup.put("minecraft:stone", 0);
        ChunkBulkBuilder.substituteBuriedCells(0, 0, 0, sizeX, sizeY, sizeZ, occluding, indices, palette, lookup);
        return sliceHash(sizeX, sizeY, sizeZ, indices, palette);
    }

    private static long sliceHash(int sizeX, int sizeY, int sizeZ, short[] indices, List<String> palette) {
        int gridLength = ViewSlice.biomeGridSpan(0, sizeX) * ViewSlice.biomeGridSpan(0, sizeY) * ViewSlice.biomeGridSpan(0, sizeZ);
        ViewSlice slice = new ViewSlice(0, 0, 0, sizeX, sizeY, sizeZ, palette, indices,
            new byte[sizeX * sizeY * sizeZ], List.of("minecraft:plains"), new short[gridLength]);
        return slice.contentHash();
    }

    private static int cellIndex(int x, int y, int z, int sizeX, int sizeZ) {
        return (((y) * sizeZ + (z)) * sizeX) + (x);
    }
}
