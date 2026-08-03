package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.render.PortalSkinRenderer.SkinRenderMode;
import art.arcane.wormholes.render.PortalSkinRenderer.SkinTransform;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;

public final class PortalSkinRendererTest
{
    private static final double EPSILON = 0.000001D;

    @Test
    public void emptySkinRoutesToNone()
    {
        assertEquals(SkinRenderMode.NONE, PortalSkinRenderer.skinRenderMode(""));
        assertEquals(SkinRenderMode.NONE, PortalSkinRenderer.skinRenderMode("   "));
        assertEquals(SkinRenderMode.NONE, PortalSkinRenderer.skinRenderMode(null));
    }

    @Test
    public void bucketFluidsRouteToFluidClaims()
    {
        assertEquals(SkinRenderMode.FLUID_CLAIMS, PortalSkinRenderer.skinRenderMode("minecraft:water"));
        assertEquals(SkinRenderMode.FLUID_CLAIMS, PortalSkinRenderer.skinRenderMode("minecraft:lava"));
        assertEquals(SkinRenderMode.FLUID_CLAIMS, PortalSkinRenderer.skinRenderMode("WATER"));
        assertEquals(SkinRenderMode.FLUID_CLAIMS, PortalSkinRenderer.skinRenderMode("minecraft:water[level=0]"));
    }

    @Test
    public void solidBlocksRouteToDisplay()
    {
        assertEquals(SkinRenderMode.DISPLAY, PortalSkinRenderer.skinRenderMode("minecraft:glass"));
        assertEquals(SkinRenderMode.DISPLAY, PortalSkinRenderer.skinRenderMode("minecraft:stone"));
        assertEquals(SkinRenderMode.DISPLAY, PortalSkinRenderer.skinRenderMode("minecraft:blue_ice"));
    }

    @Test
    public void fluidClaimOwnerIsStableAndDistinctFromProjectionOwner()
    {
        UUID portalId = UUID.fromString("4bf03082-84bb-4f14-bd51-138f08c71202");

        UUID first = PortalSkinRenderer.fluidClaimOwnerId(portalId);
        UUID second = PortalSkinRenderer.fluidClaimOwnerId(portalId);

        assertEquals(first, second);
        assertNotEquals(portalId, first);
    }

    @Test
    public void zNormalPaneScalesTheApertureAndThinsAlongTheNormal()
    {
        AxisAlignedBB area = new AxisAlignedBB(10.0D, 12.0D, 64.0D, 67.0D, 8.0D, 8.0D);
        SkinTransform transform = PortalSkinRenderer.skinTransforms(area, Axis.Z, 8.5D, 0.2D);

        assertEquals(2.0D, transform.scaleX(), EPSILON);
        assertEquals(3.0D, transform.scaleY(), EPSILON);
        assertEquals(0.2D, transform.scaleZ(), EPSILON);
        assertEquals(10.0D, transform.anchorX(), EPSILON);
        assertEquals(64.0D, transform.anchorY(), EPSILON);
        assertEquals(8.5D, transform.anchorZ(), EPSILON);
        assertEquals(0.0D, transform.translationX(), EPSILON);
        assertEquals(0.0D, transform.translationY(), EPSILON);
        assertEquals(-0.1D, transform.translationZ(), EPSILON);
        assertNormalSlabCenteredOnPlane(transform.anchorZ(), transform.translationZ(), transform.scaleZ(), 8.5D, 0.2D);
    }

    @Test
    public void xNormalPaneThinsAlongXAndCoversTheYzAperture()
    {
        AxisAlignedBB area = new AxisAlignedBB(8.0D, 8.0D, 64.0D, 67.0D, 10.0D, 12.0D);
        SkinTransform transform = PortalSkinRenderer.skinTransforms(area, Axis.X, 8.5D, 0.4D);

        assertEquals(0.4D, transform.scaleX(), EPSILON);
        assertEquals(3.0D, transform.scaleY(), EPSILON);
        assertEquals(2.0D, transform.scaleZ(), EPSILON);
        assertEquals(8.5D, transform.anchorX(), EPSILON);
        assertEquals(64.0D, transform.anchorY(), EPSILON);
        assertEquals(10.0D, transform.anchorZ(), EPSILON);
        assertEquals(-0.2D, transform.translationX(), EPSILON);
        assertEquals(0.0D, transform.translationY(), EPSILON);
        assertEquals(0.0D, transform.translationZ(), EPSILON);
        assertNormalSlabCenteredOnPlane(transform.anchorX(), transform.translationX(), transform.scaleX(), 8.5D, 0.4D);
    }

    @Test
    public void yNormalPaneThinsAlongYAndCoversTheXzAperture()
    {
        AxisAlignedBB area = new AxisAlignedBB(10.0D, 12.0D, 64.0D, 64.0D, 8.0D, 11.0D);
        SkinTransform transform = PortalSkinRenderer.skinTransforms(area, Axis.Y, 64.5D, 1.0D);

        assertEquals(2.0D, transform.scaleX(), EPSILON);
        assertEquals(1.0D, transform.scaleY(), EPSILON);
        assertEquals(3.0D, transform.scaleZ(), EPSILON);
        assertEquals(10.0D, transform.anchorX(), EPSILON);
        assertEquals(64.5D, transform.anchorY(), EPSILON);
        assertEquals(8.0D, transform.anchorZ(), EPSILON);
        assertEquals(0.0D, transform.translationX(), EPSILON);
        assertEquals(-0.5D, transform.translationY(), EPSILON);
        assertEquals(0.0D, transform.translationZ(), EPSILON);
        assertNormalSlabCenteredOnPlane(transform.anchorY(), transform.translationY(), transform.scaleY(), 64.5D, 1.0D);
    }

    @Test
    public void everySkinPaneIsExactlyOneBlockThickAlongTheNormal()
    {
        List<SkinTransform> squarePanes = PortalSkinRenderer.buildPanes(portal(0, 0, 64, 66, 0, 2));
        List<SkinTransform> singleCellPanes = PortalSkinRenderer.buildPanes(portal(0, 0, 64, 64, 0, 0));

        assertFalse(squarePanes.isEmpty());
        assertFalse(singleCellPanes.isEmpty());
        for (SkinTransform pane : squarePanes)
        {
            assertEquals(1.0D, pane.scaleX(), EPSILON);
            assertNormalSlabCenteredOnPlane(pane.anchorX(), pane.translationX(), pane.scaleX(), 0.0D, 1.0D);
        }
        for (SkinTransform pane : singleCellPanes)
        {
            assertEquals(1.0D, pane.scaleX(), EPSILON);
            assertNormalSlabCenteredOnPlane(pane.anchorX(), pane.translationX(), pane.scaleX(), 0.0D, 1.0D);
        }
    }

    private static ILocalPortal portal(int x1, int x2, int y1, int y2, int z1, int z2)
    {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("worldKey", "minecraft:overworld");
        values.put("x1", Integer.valueOf(x1));
        values.put("y1", Integer.valueOf(y1));
        values.put("z1", Integer.valueOf(z1));
        values.put("x2", Integer.valueOf(x2));
        values.put("y2", Integer.valueOf(y2));
        values.put("z2", Integer.valueOf(z2));
        PortalStructure structure = new PortalStructure();
        structure.setArea(new Cuboid(values));
        PortalFrame frame = PortalFrame.canonical(Direction.E);
        Vector origin = new Vector(0.0D, y1, z1);
        return (ILocalPortal) Proxy.newProxyInstance(ILocalPortal.class.getClassLoader(),
            new Class<?>[] { ILocalPortal.class }, (proxy, method, arguments) -> switch(method.getName())
            {
                case "getStructure" -> structure;
                case "getFrame" -> frame;
                case "getOrigin" -> origin;
                case "toString" -> "PortalSkinRendererTestPortal";
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static void assertNormalSlabCenteredOnPlane(double anchor, double translation, double scale, double planeCoordinate, double thickness)
    {
        double low = anchor + translation;
        double high = low + scale;
        assertEquals(planeCoordinate - (thickness / 2.0D), low, EPSILON);
        assertEquals(planeCoordinate + (thickness / 2.0D), high, EPSILON);
    }
}
