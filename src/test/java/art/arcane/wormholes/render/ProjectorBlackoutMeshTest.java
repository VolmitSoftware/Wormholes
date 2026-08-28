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
    public void panelsCoverOnlyTheExactFarSlice() {
        LongOpenHashSet geometry = box(0, 4, 0, 4, 0, 4);
        ProjectorBlackoutMesh.Result result = build(geometry, Direction.S);

        assertFalse(result.fallback());
        assertEquals(expectedFarCap(geometry, Direction.S), expandedFaces(result.panels()));
        assertTrue(result.panels().stream().allMatch(panel -> panel.axis() == 2));
        assertTrue(result.panels().stream().allMatch(panel -> panel.sign() == -1));
        assertTrue(result.panels().stream().allMatch(panel -> panel.plane() == 0));
    }

    @Test
    public void boundaryMeshClosesFarCeilingFloorAndSideFacesWithoutANearCap() {
        ProjectorBlackoutBoundary boundary = new ProjectorBlackoutBoundary();
        Set<UnitFace> expected = new HashSet<UnitFace>();
        for (int x = 0; x <= 2; x++) {
            for (int y = 0; y <= 2; y++) {
                addFace(boundary, expected, x, y, 0, 2, -1);
            }
        }
        for (int z = 0; z <= 3; z++) {
            for (int y = 0; y <= 2; y++) {
                addFace(boundary, expected, 0, y, z, 0, -1);
                addFace(boundary, expected, 2, y, z, 0, 1);
            }
            for (int x = 0; x <= 2; x++) {
                addFace(boundary, expected, x, 0, z, 1, -1);
                addFace(boundary, expected, x, 2, z, 1, 1);
            }
        }

        ProjectorBlackoutMesh.Result result = ProjectorBlackoutMesh.build(boundary);

        assertFalse(result.fallback());
        assertEquals(expected, expandedFaces(result.panels()));
        assertTrue(result.panels().stream().noneMatch(panel -> panel.axis() == 2 && panel.sign() > 0));
    }

    @Test
    public void everyPortalNormalUsesOnlyItsOutwardFarFace() {
        LongOpenHashSet geometry = box(-3, 5, 20, 25, 7, 13);

        for (Direction normal : Direction.values()) {
            ProjectorBlackoutMesh.Result result = build(geometry, normal);
            int normalAxis = axis(normal);
            int outwardSign = -sign(normal);

            assertFalse(result.fallback(), normal.name());
            assertEquals(expectedFarCap(geometry, normal), expandedFaces(result.panels()), normal.name());
            assertTrue(result.panels().stream().allMatch(panel -> panel.axis() == normalAxis), normal.name());
            assertTrue(result.panels().stream().allMatch(panel -> panel.sign() == outwardSign), normal.name());
        }
    }

    @Test
    public void jaggedFarSliceDoesNotFillItsBoundingBox() {
        LongOpenHashSet geometry = box(0, 4, 0, 4, -3, 2);
        for (int x = 0; x <= 4; x++) {
            for (int y = 0; y <= 4; y++) {
                geometry.remove(ProjectionCellKey.pack(x, y, -3));
            }
        }
        for (int x = 0; x <= 4; x++) {
            geometry.add(ProjectionCellKey.pack(x, 0, -3));
        }
        for (int y = 0; y <= 4; y++) {
            geometry.add(ProjectionCellKey.pack(0, y, -3));
        }

        ProjectorBlackoutMesh.Result result = build(geometry, Direction.S);
        Set<UnitFace> faces = expandedFaces(result.panels());

        assertEquals(expectedFarCap(geometry, Direction.S), faces);
        assertEquals(9, faces.size());
        assertFalse(faces.contains(new UnitFace(2, -1, -3, 4, 4)));
    }

    @Test
    public void oneLayerVolumeReceivesOnlyAnInsetFarCap() {
        LongOpenHashSet geometry = box(0, 2, 0, 2, 0, 0);
        ProjectorBlackoutMesh.Result result = build(geometry, Direction.S);

        assertEquals(expectedFarCap(geometry, Direction.S), expandedFaces(result.panels()));
        assertEquals(9, expandedFaces(result.panels()).size());
        assertInsideFarSlice(result.panels(), Direction.S, 0);
    }

    @Test
    public void panelsArePlacedWhollyInsideTheFarSliceForEveryNormal() {
        LongOpenHashSet geometry = box(-2, 3, 4, 8, -7, -1);

        for (Direction normal : Direction.values()) {
            int farCoordinate = farCoordinate(geometry, normal);
            ProjectorBlackoutMesh.Result result = build(geometry, normal);

            assertInsideFarSlice(result.panels(), normal, farCoordinate);
        }
    }

    @Test
    public void negativeChunkAndSixtyFourBlockSplitsHaveNoGapsOrOverlaps() {
        LongOpenHashSet geometry = box(-17, 16, -2, 65, -17, 16);
        ProjectorBlackoutMesh.Result result = build(geometry, Direction.S);
        Set<UnitFace> expanded = expandedFaces(result.panels());
        int expandedArea = 0;
        for (ProjectorBlackoutMesh.Panel panel : result.panels()) {
            expandedArea += panel.uSize() * panel.vSize();
            assertTrue(panel.uSize() <= ProjectorBlackoutMesh.MAX_PANEL_SPAN);
            assertTrue(panel.vSize() <= ProjectorBlackoutMesh.MAX_PANEL_SPAN);
        }

        assertFalse(result.fallback());
        assertEquals(expandedArea, expanded.size());
        assertEquals(expectedFarCap(geometry, Direction.S), expanded);
    }

    @Test
    public void depth64DoorFrustumsStayOnBoundedFarCapPanels() {
        int[][] apertures = new int[][] {{1, 2}, {3, 3}};
        for (int[] aperture : apertures) {
            LongOpenHashSet geometry = taperedFrustum(aperture[0], aperture[1], 64, 48);
            ProjectorBlackoutMesh.Result result = build(geometry, Direction.S);

            assertFalse(result.fallback());
            assertFalse(result.panels().isEmpty());
            assertTrue(result.panels().size() <= ProjectorBlackoutMesh.MAX_PANELS);
            assertTrue(result.panels().stream().allMatch(panel -> panel.axis() == 2));
            assertTrue(result.panels().stream().allMatch(panel -> panel.sign() == -1));
            assertTrue(result.panels().stream().allMatch(panel -> panel.plane() == 0));
            assertTrue(result.panels().stream().anyMatch(panel -> panel.uSize() > 1 || panel.vSize() > 1));
            assertTrue(result.panels().size() < geometry.size());
            assertEquals(expectedFarCap(geometry, Direction.S), expandedFaces(result.panels()));
        }
    }

    @Test
    public void excessiveFragmentationDropsTheDisplayMesh() {
        LongOpenHashSet geometry = new LongOpenHashSet();
        for (int component = 0; component <= ProjectorBlackoutMesh.MAX_PANELS; component++) {
            geometry.add(ProjectionCellKey.pack(component * 16, 0, 0));
        }

        ProjectorBlackoutMesh.Result result = build(geometry, Direction.S);

        assertTrue(result.fallback());
        assertFalse(result.hasProjection());
        assertTrue(result.panels().isEmpty());
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

        assertEquals(build(geometry, Direction.W), build(reversed, Direction.W));
    }

    private static ProjectorBlackoutMesh.Result build(LongOpenHashSet geometry, Direction normal) {
        int normalMin = Integer.MAX_VALUE;
        int normalMax = Integer.MIN_VALUE;
        int normalAxis = axis(normal);
        LongIterator iterator = geometry.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            int coordinate = coordinate(key, normalAxis);
            normalMin = Math.min(normalMin, coordinate);
            normalMax = Math.max(normalMax, coordinate);
        }
        return ProjectorBlackoutMesh.build(geometry, normal, normalMin, normalMax);
    }

    private static void addFace(ProjectorBlackoutBoundary boundary,
                                Set<UnitFace> expected,
                                int x,
                                int y,
                                int z,
                                int axis,
                                int sign) {
        boundary.add(ProjectionCellKey.pack(x, y, z), ProjectorBlackoutBoundary.faceMask(axis, sign));
        int plane = coordinate(ProjectionCellKey.pack(x, y, z), axis) + (sign > 0 ? 1 : 0);
        int u = axis == 0 ? y : x;
        int v = axis == 2 ? y : z;
        expected.add(new UnitFace(axis, sign, plane, u, v));
    }

    private static Set<UnitFace> expectedFarCap(LongOpenHashSet geometry, Direction normal) {
        Set<UnitFace> faces = new HashSet<UnitFace>();
        int normalAxis = axis(normal);
        int normalSign = sign(normal);
        int farCoordinate = farCoordinate(geometry, normal);
        int outwardSign = -normalSign;
        int plane = farCoordinate + (outwardSign > 0 ? 1 : 0);
        LongIterator iterator = geometry.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            if (coordinate(key, normalAxis) != farCoordinate) {
                continue;
            }
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            int u = normalAxis == 0 ? y : x;
            int v = normalAxis == 2 ? y : z;
            faces.add(new UnitFace(normalAxis, outwardSign, plane, u, v));
        }
        return faces;
    }

    private static void assertInsideFarSlice(List<ProjectorBlackoutMesh.Panel> panels,
                                             Direction normal,
                                             int farCoordinate) {
        int normalSign = sign(normal);
        for (ProjectorBlackoutMesh.Panel panel : panels) {
            ProjectorBlackoutMesh.Transform transform = panel.transform();
            double minimum = panel.axis() == 0 ? transform.x() : panel.axis() == 1 ? transform.y() : transform.z();
            double size = panel.axis() == 0
                ? transform.scaleX()
                : panel.axis() == 1 ? transform.scaleY() : transform.scaleZ();
            assertEquals(ProjectorBlackoutMesh.PANEL_THICKNESS, size);
            if (normalSign > 0) {
                assertEquals(farCoordinate + ProjectorBlackoutMesh.PANEL_INSET, minimum, normal.name());
                assertTrue(minimum + size < farCoordinate + 1.0D, normal.name());
            } else {
                assertEquals(farCoordinate + 1.0D - ProjectorBlackoutMesh.PANEL_THICKNESS
                    - ProjectorBlackoutMesh.PANEL_INSET, minimum, normal.name());
                assertTrue(minimum > farCoordinate, normal.name());
            }
        }
    }

    private static int farCoordinate(LongOpenHashSet geometry, Direction normal) {
        int normalAxis = axis(normal);
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        LongIterator iterator = geometry.iterator();
        while (iterator.hasNext()) {
            int coordinate = coordinate(iterator.nextLong(), normalAxis);
            minimum = Math.min(minimum, coordinate);
            maximum = Math.max(maximum, coordinate);
        }
        return sign(normal) > 0 ? minimum : maximum;
    }

    private static int coordinate(long key, int axis) {
        return axis == 0
            ? ProjectionCellKey.unpackX(key)
            : axis == 1 ? ProjectionCellKey.unpackY(key) : ProjectionCellKey.unpackZ(key);
    }

    private static int axis(Direction direction) {
        return direction.x() != 0 ? 0 : direction.y() != 0 ? 1 : 2;
    }

    private static int sign(Direction direction) {
        return direction.x() + direction.y() + direction.z();
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

    private static LongOpenHashSet taperedFrustum(int width, int height, int depth, int farPadding) {
        LongOpenHashSet geometry = new LongOpenHashSet(220_000);
        for (int z = 0; z < depth; z++) {
            int padding = (farPadding * (depth - 1 - z)) / (depth - 1);
            int minX = -(width / 2) - padding;
            int minY = 64 - (height / 2) - padding;
            int maxX = minX + width + (padding * 2) - 1;
            int maxY = minY + height + (padding * 2) - 1;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    geometry.add(ProjectionCellKey.pack(x, y, z));
                }
            }
        }
        return geometry;
    }

    private record UnitFace(int axis, int sign, int plane, int u, int v) {
    }
}
