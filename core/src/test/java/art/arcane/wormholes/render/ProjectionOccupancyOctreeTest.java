package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.junit.jupiter.api.Test;

final class ProjectionOccupancyOctreeTest {
    @Test
    void emptyVolumeReportsTheLargestSkip() {
        ProjectionOccupancyOctree octree = new ProjectionOccupancyOctree();
        octree.rebuild(new LongOpenHashSet());

        assertTrue(octree.isEmpty());
        assertEquals(ProjectionOccupancyOctree.MAX_SKIP_LOG, octree.largestEmptyLog(0, 0, 0));
        assertEquals(ProjectionOccupancyOctree.MAX_SKIP_LOG, octree.largestEmptyLog(40, -3, 12));
    }

    @Test
    void occupiedCellCannotSkipItsEightCube() {
        ProjectionOccupancyOctree octree = new ProjectionOccupancyOctree();
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(ProjectionCellKey.pack(0, 0, 0));
        octree.rebuild(cells);

        assertEquals(0, octree.largestEmptyLog(0, 0, 0));
        assertEquals(0, octree.largestEmptyLog(7, 0, 0));
        assertEquals(ProjectionOccupancyOctree.MIN_SKIP_LOG, octree.largestEmptyLog(8, 0, 0));
        assertEquals(4, octree.largestEmptyLog(16, 0, 0));
        assertEquals(5, octree.largestEmptyLog(32, 0, 0));
        assertEquals(ProjectionOccupancyOctree.MAX_SKIP_LOG, octree.largestEmptyLog(64, 0, 0));
    }

    @Test
    void cubeExitIsTheFirstFarFaceAlongTheRay() {
        double t = ProjectionOccupancyOctree.cubeExitT(
            1, 1, 1, 3, 0.5D, 1.5D, 1.5D, 1.0D, 0.0D, 0.0D, 1, 0, 0);
        assertEquals(7.5D, t, 1.0E-9D);
    }

    @Test
    void rayBoundsIncludeBothEndpointsAndResetAfterGeometryChanges() {
        ProjectionOccupancyOctree octree = new ProjectionOccupancyOctree();
        LongOpenHashSet cells = new LongOpenHashSet();
        cells.add(ProjectionCellKey.pack(-3, 20, 7));
        octree.rebuild(cells);

        assertTrue(octree.intersectsRayBounds(-3, 20, 7, 4, 5, 6));
        assertTrue(octree.intersectsRayBounds(4, 5, 6, -3, 20, 7));
        assertFalse(octree.intersectsRayBounds(-2, 20, 7, 4, 5, 6));
        assertFalse(octree.intersectsRayBounds(-3, 19, 7, 4, 5, 6));
        assertFalse(octree.intersectsRayBounds(-3, 20, 6, 4, 5, 6));
        cells.clear();
        cells.add(ProjectionCellKey.pack(9, -12, -8));
        octree.rebuild(cells);
        assertFalse(octree.intersectsRayBounds(-3, 20, 7, 4, 5, 6));
        assertTrue(octree.intersectsRayBounds(9, -12, -8, 10, 20, 7));
        octree.rebuild(null);
        assertFalse(octree.intersectsRayBounds(9, -12, -8, 10, 20, 7));
    }
}
