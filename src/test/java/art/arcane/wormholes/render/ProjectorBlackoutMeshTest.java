package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import art.arcane.wormholes.util.Direction;

public final class ProjectorBlackoutMeshTest {
    @Test
    public void panelsExactlyCoverShellInteriorBoundaryForBothThicknesses() {
        LongOpenHashSet geometry = box(0, 4, 0, 4, 0, 4);

        for (int thickness = 1; thickness <= 2; thickness++) {
            ProjectorBlackoutMesh.Result result = ProjectorBlackoutMesh.build(
                geometry, Direction.S, Direction.E, Direction.U, 0, 4, thickness);

            assertFalse(result.fallback());
            assertFalse(result.panels().isEmpty());
            assertEquals(expectedFaces(geometry, Direction.S, Direction.E, Direction.U, 0, 4, thickness),
                expandedFaces(result.panels()));
            assertTrue(result.panels().stream().noneMatch(panel -> panel.axis() == 2 && panel.plane() == 5));
        }
    }

    @Test
    public void shuffledGeometryProducesTheSameDeterministicMesh() {
        LongOpenHashSet geometry = box(-3, 5, 20, 25, 7, 13);
        List<Long> keys = new ArrayList<Long>(geometry.size());
        LongIterator iterator = geometry.iterator();
        while (iterator.hasNext()) {
            keys.add(Long.valueOf(iterator.nextLong()));
        }
        Collections.reverse(keys);
        LongOpenHashSet reversed = new LongOpenHashSet(keys.size());
        for (Long key : keys) {
            reversed.add(key.longValue());
        }

        ProjectorBlackoutMesh.Result first = ProjectorBlackoutMesh.build(
            geometry, Direction.W, Direction.N, Direction.U, -3, 5, 2);
        ProjectorBlackoutMesh.Result second = ProjectorBlackoutMesh.build(
            reversed, Direction.W, Direction.N, Direction.U, -3, 5, 2);

        assertEquals(first, second);
    }

    @Test
    public void panelsStayInsideTheShellSideOfTheirSharedPlane() {
        LongOpenHashSet geometry = box(0, 4, 0, 4, 0, 4);
        ProjectorBlackoutMesh.Result result = ProjectorBlackoutMesh.build(
            geometry, Direction.S, Direction.E, Direction.U, 0, 4, 2);

        for (ProjectorBlackoutMesh.Panel panel : result.panels()) {
            ProjectorBlackoutMesh.Transform transform = panel.transform();
            double minimum = panel.axis() == 0 ? transform.x() : panel.axis() == 1 ? transform.y() : transform.z();
            double size = panel.axis() == 0
                ? transform.scaleX()
                : panel.axis() == 1 ? transform.scaleY() : transform.scaleZ();
            assertEquals(ProjectorBlackoutMesh.PANEL_THICKNESS, size);
            assertEquals(panel.sign() > 0
                    ? panel.plane() - ProjectorBlackoutMesh.PANEL_THICKNESS - ProjectorBlackoutMesh.PANEL_INSET
                    : panel.plane() + ProjectorBlackoutMesh.PANEL_INSET,
                minimum);
        }
    }

    @Test
    public void excessiveExactPanelsSelectCompleteBlockFallback() {
        LongOpenHashSet geometry = new LongOpenHashSet();
        for (int component = 0; component <= ProjectorBlackoutMesh.MAX_PANELS; component++) {
            int originX = component * 5;
            for (int x = originX; x < originX + 3; x++) {
                for (int y = 0; y < 3; y++) {
                    for (int z = 0; z < 2; z++) {
                        geometry.add(ProjectionCellKey.pack(x, y, z));
                    }
                }
            }
        }

        ProjectorBlackoutMesh.Result result = ProjectorBlackoutMesh.build(
            geometry, Direction.S, Direction.E, Direction.U, 0, 1, 1);

        assertTrue(result.fallback());
        assertTrue(result.panels().isEmpty());
        assertTrue(result.unitFaces() > ProjectorBlackoutMesh.MAX_PANELS);
    }

    private static Set<UnitFace> expectedFaces(LongOpenHashSet geometry,
                                               Direction normal,
                                               Direction right,
                                               Direction up,
                                               int normalMin,
                                               int normalMax,
                                               int thickness) {
        LongOpenHashSet shell = new LongOpenHashSet();
        LongIterator iterator = geometry.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            if (ProjectorBlackoutSeal.sealsGeometryCell(
                key, geometry, normal, right, up, normalMin, normalMax, thickness)) {
                shell.add(key);
            }
        }

        Set<UnitFace> faces = new HashSet<UnitFace>();
        LongIterator shellIterator = shell.iterator();
        while (shellIterator.hasNext()) {
            long key = shellIterator.nextLong();
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            for (Direction direction : Direction.values()) {
                long neighbor = ProjectionCellKey.pack(
                    x + direction.x(), y + direction.y(), z + direction.z());
                if (!geometry.contains(neighbor) || shell.contains(neighbor)) {
                    continue;
                }
                int axis = direction.x() != 0 ? 0 : direction.y() != 0 ? 1 : 2;
                int sign = direction.x() + direction.y() + direction.z();
                int coordinate = axis == 0 ? x : axis == 1 ? y : z;
                int plane = coordinate + (sign > 0 ? 1 : 0);
                int u = axis == 0 ? y : x;
                int v = axis == 2 ? y : z;
                faces.add(new UnitFace(axis, sign, plane, u, v));
            }
        }
        return faces;
    }

    private static Set<UnitFace> expandedFaces(List<ProjectorBlackoutMesh.Panel> panels) {
        Set<UnitFace> faces = new HashSet<UnitFace>();
        for (ProjectorBlackoutMesh.Panel panel : panels) {
            for (int vOffset = 0; vOffset < panel.vSize(); vOffset++) {
                for (int uOffset = 0; uOffset < panel.uSize(); uOffset++) {
                    assertTrue(faces.add(new UnitFace(
                        panel.axis(), panel.sign(), panel.plane(),
                        panel.u() + uOffset, panel.v() + vOffset)));
                }
            }
        }
        return faces;
    }

    private static LongOpenHashSet box(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        LongOpenHashSet geometry = new LongOpenHashSet();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    geometry.add(ProjectionCellKey.pack(x, y, z));
                }
            }
        }
        return geometry;
    }

    private record UnitFace(int axis, int sign, int plane, int u, int v) {
    }
}
