package art.arcane.wormholes.modded;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.FitMode;
import art.arcane.optics.shape.PlaneShape;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.shape.ShapeRaster;
import art.arcane.optics.shape.Shapes;
import art.arcane.wormholes.portal.Portal;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MinecraftPortalShapeTest extends MinecraftTestBase {
    private static final ShapeDescriptor CIRCLE = ShapeDescriptor.parse("circle");
    private static final int CIRCLE_CELLS = ShapeRaster.of(PlaneShape.fit(Shapes.circle(1.0D), FitMode.CONTAIN, 7, 7), 4, 0.5D).insideCount();

    @Test
    public void theShapeRoundTripsThroughValuesWhileTheStructureKeepsEveryBuiltCell() {
        MinecraftPortal portal = wall(Map.of());
        assertTrue(portal.setApertureShape(CIRCLE));
        Map<String, Object> written = portal.write();
        assertEquals("circle(radius=1)", written.get("apertureShape"));
        assertEquals(49, ((List<?>) ((Map<?, ?>) written.get("structure")).get("blocks")).size());
        MinecraftPortal read = MinecraftPortal.read(written);
        assertEquals(CIRCLE, read.getApertureShape());
        assertEquals(CIRCLE_CELLS, read.getGeometry().getBlockPositions().size());
        assertEquals(49, read.getBuiltGeometry().getBlockPositions().size());
        assertTrue(read.setApertureShape(ShapeDescriptor.FULL));
        assertFalse(read.write().containsKey("apertureShape"));
        assertEquals(49, read.getGeometry().getBlockPositions().size());
        assertNull(read.shapeOutline());
    }

    @Test
    public void effectiveCellsAreTheBuiltCellsInsideTheRaster() {
        MinecraftPortal portal = wall(Map.of());
        long revision = portal.getGeometry().getRevision();
        assertTrue(portal.setApertureShape(CIRCLE));
        assertEquals(CIRCLE_CELLS, portal.getGeometry().getBlockPositions().size());
        assertFalse(portal.getGeometry().containsBlock(0, 64, 0));
        assertTrue(portal.getGeometry().containsBlock(3, 67, 0));
        assertTrue(portal.getBuiltGeometry().containsBlock(0, 64, 0));
        assertTrue(portal.getGeometry().getRevision() != revision);
        assertNotNull(portal.shapeOutline());
    }

    @Test
    public void crossingIsExactAtTheEyeForWallPortals() {
        MinecraftPortal portal = wall(Map.of());
        assertTrue(portal.admits(new Vec3d(0.3D, 64.0D, 0.5D), 1.62D));
        assertTrue(portal.setApertureShape(CIRCLE));
        assertTrue(portal.admits(new Vec3d(3.3D, 64.0D, 0.5D), 1.62D));
        assertFalse(portal.admits(new Vec3d(0.3D, 64.0D, 0.5D), 1.62D));
        assertTrue(portal.admits(new Vec3d(3.5D, 67.5D, 0.5D), 0.0D));
        assertFalse(portal.admits(new Vec3d(0.2D, 70.8D, 0.5D), 0.0D));
    }

    @Test
    public void aShapeThatLeavesNoCellIsRefusedAndAnUnreadableStoredShapeLoadsFull() {
        MinecraftPortal portal = wall(Map.of());
        assertTrue(portal.setApertureShape(CIRCLE));
        ShapeDescriptor tiny = ShapeDescriptor.parse("circle(radius=0.05)");
        assertFalse(portal.acceptsApertureShape(tiny));
        assertFalse(portal.setApertureShape(tiny));
        assertEquals(CIRCLE, portal.getApertureShape());
        MinecraftPortal unreadable = wall(Map.of("apertureShape", "circle(radius="));
        assertEquals(ShapeDescriptor.FULL, unreadable.getApertureShape());
        assertEquals(49, unreadable.getGeometry().getBlockPositions().size());
    }

    @Test
    public void turningTheFrameReorientsTheShape() {
        MinecraftPortal portal = wall(Map.of());
        assertTrue(portal.setApertureShape(ShapeDescriptor.parse("heart")));
        int top = row(portal, 69);
        int bottom = row(portal, 65);
        assertTrue(top > bottom);
        portal.setFrame(Frame.canonical(Face.S).rotateClockwise().rotateClockwise());
        assertEquals(bottom, row(portal, 69));
        assertEquals(top, row(portal, 65));
    }

    private static int row(MinecraftPortal portal, int y) {
        int count = 0;
        for (Vec3d cell : portal.getGeometry().getBlockPositions()) {
            count += cell.blockY() == y ? 1 : 0;
        }
        return count;
    }

    private static MinecraftPortal wall(Map<String, Object> extra) {
        ApertureCells geometry = new ApertureCells();
        geometry.setArea(new Box(0.0D, 6.999D, 64.0D, 70.999D, 0.0D, 0.999D));
        UUID id = UUID.randomUUID();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("owner", id.toString());
        values.put("type", "PORTAL");
        values.putAll(extra);
        return new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(id, geometry.getApertureCenter(), "Shaped",
            Frame.canonical(Face.S), true), geometry, "minecraft:overworld", values));
    }
}
