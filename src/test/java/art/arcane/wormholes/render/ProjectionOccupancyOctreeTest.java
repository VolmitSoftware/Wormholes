package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.junit.jupiter.api.Test;

final class ProjectionOccupancyOctreeTest {
    @Test
    void emptyVolumeReportsTheLargestSkip() {
        ProjectionOccupancyOctree octree = new ProjectionOccupancyOctree();
        octree.rebuild(new LongOpenHashSet());

        assertTrue(octree.isEmpty());
        assertEquals(ProjectionOccupancyOctree.LARGE_LOG, octree.largestEmptyLog(0, 0, 0));
        assertEquals(ProjectionOccupancyOctree.LARGE_LOG, octree.largestEmptyLog(40, -3, 12));
    }

    @Test
    void occupiedCellCannotSkipItsEightCube() {
        ProjectionOccupancyOctree octree = new ProjectionOccupancyOctree();
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(ProjectionCellKey.pack(0, 0, 0));
        octree.rebuild(cells);

        assertEquals(0, octree.largestEmptyLog(0, 0, 0));
        assertEquals(0, octree.largestEmptyLog(7, 0, 0));
        assertEquals(ProjectionOccupancyOctree.SMALL_LOG, octree.largestEmptyLog(8, 0, 0));
        assertEquals(ProjectionOccupancyOctree.LARGE_LOG, octree.largestEmptyLog(16, 0, 0));
    }

    @Test
    void cubeExitIsTheFirstFarFaceAlongTheRay() {
        double t = ProjectionOccupancyOctree.cubeExitT(
            1, 1, 1, 3, 0.5D, 1.5D, 1.5D, 1.0D, 0.0D, 0.0D, 1, 0, 0);
        assertEquals(7.5D, t, 1.0E-9D);
    }
}
