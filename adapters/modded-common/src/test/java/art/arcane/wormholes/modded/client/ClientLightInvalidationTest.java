package art.arcane.wormholes.modded.client;

import art.arcane.optics.math.CellKeys;
import art.arcane.optics.fidelity.BlockEntitySample;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClientLightInvalidationTest {
    @Test
    public void interiorChangesDoNotRebuildUnchangedBoundaryNeighbors() {
        RecordingSurface surface = new RecordingSurface();
        ClientLightPatches patches = new ClientLightPatches(surface);
        long center = CellKeys.pack(8, 8, 8);
        long corner = CellKeys.pack(0, 0, 15);
        refresh(patches, new long[]{center, corner}, new int[]{0x75, 0x75});
        surface.changes.clear();

        refresh(patches, new long[]{center, corner}, new int[]{0x76, 0x75});
        patches.tick();

        assertEquals(List.of(new Change(0, 0, 0, 0)), surface.changes);
        assertEquals(6, patches.value(false, 8, 8, 8, 0));
        assertEquals(7, patches.value(true, 8, 8, 8, 0));
        assertEquals(5, patches.value(false, 0, 0, 15, 0));
    }

    @Test
    public void eachChangedFaceMarksOnlyItsAdjacentDirection() {
        int[][] cells = {{0, 8, 8}, {15, 8, 8}, {8, 0, 8}, {8, 15, 8}, {8, 8, 0}, {8, 8, 15}};
        for (int index = 0; index < cells.length; index++) {
            RecordingSurface surface = new RecordingSurface();
            ClientLightPatches patches = new ClientLightPatches(surface);
            int[] cell = cells[index];
            long[] keys = {CellKeys.pack(cell[0], cell[1], cell[2])};
            refresh(patches, keys, new int[]{0});
            assertEquals(List.of(new Change(0, 0, 0, 1 << index)), surface.changes);
            surface.changes.clear();

            refresh(patches, keys, new int[]{0x71});
            assertEquals(List.of(new Change(0, 0, 0, 1 << index)), surface.changes);
        }
    }

    @Test
    public void cornerRemovalInvalidatesEveryAffectedDirection() {
        RecordingSurface surface = new RecordingSurface();
        ClientLightPatches patches = new ClientLightPatches(surface);
        refresh(patches, new long[]{CellKeys.pack(0, 0, 15)}, new int[]{0});
        patches.tick();
        surface.changes.clear();

        refresh(patches, new long[0], new int[0]);
        patches.tick();

        assertEquals(List.of(new Change(0, 0, 0, 37)), surface.changes);
        assertEquals(ClientLightPatches.NO_LIGHT, patches.value(false, 0, 0, 15, 0));
    }

    @Test
    public void removingOneBoundaryKeepsOtherUnchangedBoundariesOutOfTheRebuild() {
        RecordingSurface surface = new RecordingSurface();
        ClientLightPatches patches = new ClientLightPatches(surface);
        long west = CellKeys.pack(0, 8, 8);
        long east = CellKeys.pack(15, 8, 8);
        refresh(patches, new long[]{west, east}, new int[]{0x71, 0x71});
        surface.changes.clear();

        refresh(patches, new long[]{east}, new int[]{0x71});

        assertEquals(List.of(new Change(0, 0, 0, 1)), surface.changes);
    }

    @Test
    public void clearUsesTheRemovedLightMask() {
        RecordingSurface surface = new RecordingSurface();
        ClientLightPatches patches = new ClientLightPatches(surface);
        refresh(patches, new long[]{CellKeys.pack(15, 15, 0)}, new int[]{0x71});
        surface.changes.clear();

        patches.clear();

        assertEquals(List.of(new Change(0, 0, 0, 26)), surface.changes);
        assertEquals(ClientLightPatches.NO_LIGHT, patches.value(false, 15, 15, 0, 0));
        surface.changes.clear();
        patches.clear();
        assertTrue(surface.changes.isEmpty());
    }

    @Test
    public void unmappedBoundaryCellsDoNotExpandRebuildsAndZeroLightRemainsMapped() {
        RecordingSurface surface = new RecordingSurface();
        ClientLightPatches patches = new ClientLightPatches(surface);
        refresh(patches, new long[]{CellKeys.pack(8, 8, 8), CellKeys.pack(0, 0, 0)}, new int[]{0, -1});
        patches.tick();

        assertEquals(List.of(new Change(0, 0, 0, 0)), surface.changes);
        assertEquals(0, patches.value(false, 8, 8, 8, 0));
        assertEquals(ClientLightPatches.NO_LIGHT, patches.value(false, 0, 0, 0, 0));
    }

    @Test
    public void unchangedLightDoesNotRebuild() {
        RecordingSurface surface = new RecordingSurface();
        ClientLightPatches patches = new ClientLightPatches(surface);
        long[] cells = {CellKeys.pack(0, 0, 0), CellKeys.pack(8, 8, 8)};
        refresh(patches, cells, new int[]{0x71, 0x45});
        surface.changes.clear();

        refresh(patches, cells, new int[]{0x71, 0x45});
        patches.tick();

        assertTrue(surface.changes.isEmpty());
    }

    @Test
    public void skyDarkeningUsesExistingMaskBoundaries() {
        RecordingSurface surface = new RecordingSurface();
        ClientLightPatches patches = new ClientLightPatches(surface);
        refresh(patches, new long[]{CellKeys.pack(0, 15, 8)}, new int[]{0x75});
        patches.tick();
        surface.changes.clear();

        surface.darken = 3;
        patches.tick();

        assertEquals(List.of(new Change(0, 0, 0, 9)), surface.changes);
        assertEquals(10, patches.value(true, 0, 15, 8, 3));
    }

    private static void refresh(ClientLightPatches patches, long[] cells, int[] values) {
        IntArrayList portals = new IntArrayList(cells.length);
        for (int index = 0; index < cells.length; index++) {
            portals.add(index);
        }
        patches.refresh(0, 0, 0, new LongArrayList(cells), portals, (cell, portal) -> values[portal]);
    }

    private record Change(int x, int y, int z, int borders) {
    }

    private static final class RecordingSurface implements ClientViewSurface {
        private final List<Change> changes = new ArrayList<>();
        private int darken;

        @Override
        public boolean chunkLoaded(int chunkX, int chunkZ) {
            return true;
        }

        @Override
        public BlockState state(int x, int y, int z) {
            return null;
        }

        @Override
        public void write(int x, int y, int z, BlockState state) {
        }

        @Override
        public void blockEntity(int x, int y, int z, BlockEntitySample sample) {
        }

        @Override
        public void attachLight(ClientLightPatches patches) {
        }

        @Override
        public void detachLight(ClientLightPatches patches) {
        }

        @Override
        public void lightChanged(int sectionX, int sectionY, int sectionZ, int boundaryMask) {
            changes.add(new Change(sectionX, sectionY, sectionZ, boundaryMask));
        }

        @Override
        public int skyDarken() {
            return darken;
        }

        @Override
        public void flush() {
        }
    }
}
