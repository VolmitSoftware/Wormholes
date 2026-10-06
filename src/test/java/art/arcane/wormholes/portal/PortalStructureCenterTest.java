package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.Settings;
import art.arcane.optics.math.Vec3;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

public final class PortalStructureCenterTest {
    private static final double EPSILON = 1e-9D;

    @Test
    public void getCenterReturnsExpectedMidpointAfterSetArea() {
        PortalStructure structure = new PortalStructure();
        structure.setArea(cuboid(0, 64, 0, 3, 68, 1));

        Location center = structure.getCenter();

        assertEquals(2.0D, center.getX(), EPSILON);
        assertEquals(66.5D, center.getY(), EPSILON);
        assertEquals(1.0D, center.getZ(), EPSILON);
    }

    @Test
    public void restoredLocalOriginUsesGeometryRatherThanSavedInsetCenter() {
        LocalPortal portal = LocalPortalTestSupport.portal(LocalPortalTestSupport.world("center"), PortalType.PORTAL);
        portal.restore(new Portal.State(portal.getId(), new Vec3(0.4995D, 65.4995D, 1.4995D),
            portal.getName(), portal.getFrame(), true));
        assertEquals(new Vec3(0.5D, 65.5D, 1.5D), portal.getOrigin());
        assertEquals(portal.getStructure().getApertureCenter(), portal.getOrigin());
        assertEquals(0.999D, portal.getStructure().getArea().getXb(), 0.0D);
    }

    @Test
    public void getCenterReturnsDefensiveClone() {
        PortalStructure structure = new PortalStructure();
        structure.setArea(cuboid(0, 64, 0, 3, 68, 1));

        Location first = structure.getCenter();
        first.add(100.0D, 100.0D, 100.0D);
        Location second = structure.getCenter();

        assertEquals(2.0D, second.getX(), EPSILON);
        assertEquals(66.5D, second.getY(), EPSILON);
        assertEquals(1.0D, second.getZ(), EPSILON);
    }

    @Test
    public void setAreaInvalidatesCenterCache() {
        PortalStructure structure = new PortalStructure();
        structure.setArea(cuboid(0, 64, 0, 3, 68, 1));
        structure.getCenter();

        structure.setArea(cuboid(10, 10, 10, 10, 12, 10));
        Location center = structure.getCenter();

        assertEquals(10.5D, center.getX(), EPSILON);
        assertEquals(11.5D, center.getY(), EPSILON);
        assertEquals(10.5D, center.getZ(), EPSILON);
    }

    @Test
    public void apertureFacesAreImmutableCachedAndInvalidatedWithArea() {
        PortalStructure structure = new PortalStructure();
        structure.setArea(cuboid(0, 64, 0, 3, 68, 1));

        List<Box> first = structure.getCachedApertureFaces(Face.N);
        List<Box> second = structure.getCachedApertureFaces(Face.N);

        assertSame(first, second);
        assertThrows(UnsupportedOperationException.class, first::clear);

        structure.setArea(cuboid(10, 10, 10, 10, 12, 10));
        List<Box> rebuilt = structure.getCachedApertureFaces(Face.N);

        assertNotSame(first, rebuilt);
        assertEquals(1, rebuilt.size());
    }

    @Test
    public void geometryRevisionAdvancesOnlyWhenStructureCachesAreInvalidated() {
        PortalStructure structure = new PortalStructure();
        long initialRevision = structure.getRevision();

        structure.setArea(cuboid(0, 64, 0, 3, 68, 1));
        long firstAreaRevision = structure.getRevision();
        structure.getCachedApertureFaces(Face.N);
        structure.getCenter();

        assertEquals(initialRevision + 1L, firstAreaRevision);
        assertEquals(firstAreaRevision, structure.getRevision());

        structure.setArea(cuboid(10, 10, 10, 10, 12, 10));

        assertEquals(firstAreaRevision + 1L, structure.getRevision());
    }

    @Test
    public void rebuildCaptureZoneUsesTheLiveSettingsRadius() {
        double previous = Settings.CAPTURE_ZONE_RADIUS;
        try {
            Settings.CAPTURE_ZONE_RADIUS = 8.0D;
            PortalStructure structure = new PortalStructure();
            structure.setArea(cuboid(0, 64, 0, 2, 66, 1));
            Box area = structure.getArea();
            Box initial = structure.getCaptureZone();

            assertEquals(area.min().getX() - 8.0D, initial.min().getX(), EPSILON);
            assertEquals(area.max().getX() + 8.0D, initial.max().getX(), EPSILON);

            Settings.CAPTURE_ZONE_RADIUS = 16.0D;
            Box stale = structure.getCaptureZone();
            assertEquals(initial.min().getX(), stale.min().getX(), EPSILON);
            assertEquals(initial.max().getX(), stale.max().getX(), EPSILON);

            structure.rebuildCaptureZone();
            Box rebuilt = structure.getCaptureZone();
            assertEquals(area.min().getX() - 16.0D, rebuilt.min().getX(), EPSILON);
            assertEquals(area.max().getX() + 16.0D, rebuilt.max().getX(), EPSILON);
            assertEquals(area.min().getY() - 16.0D, rebuilt.min().getY(), EPSILON);
            assertEquals(area.max().getY() + 16.0D, rebuilt.max().getY(), EPSILON);
            assertEquals(area.min().getZ() - 16.0D, rebuilt.min().getZ(), EPSILON);
            assertEquals(area.max().getZ() + 16.0D, rebuilt.max().getZ(), EPSILON);
        } finally {
            Settings.CAPTURE_ZONE_RADIUS = previous;
        }
    }

    private static Cuboid cuboid(int x1, int y1, int z1, int x2, int y2, int z2) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("worldKey", "minecraft:overworld");
        map.put("x1", Integer.valueOf(x1));
        map.put("y1", Integer.valueOf(y1));
        map.put("z1", Integer.valueOf(z1));
        map.put("x2", Integer.valueOf(x2));
        map.put("y2", Integer.valueOf(y2));
        map.put("z2", Integer.valueOf(z2));
        return new AssertSafeCuboid(map);
    }

    private static final class AssertSafeCuboid extends Cuboid {
        private AssertSafeCuboid(Map<String, Object> map) {
            super(map);
        }

        @Override
        public Vector getCornerVector(Face x, Face y, Face z) {
            double s = 0.999D;
            return new Vector(x.x() == 1 ? (x2 + s) : x1, y.y() == 1 ? (y2 + s) : y1, z.z() == 1 ? (z2 + s) : z1);
        }
    }
}
