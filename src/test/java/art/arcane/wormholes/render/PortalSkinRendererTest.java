package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.PortalSkinRenderer.SkinRenderMode;
import art.arcane.wormholes.render.PortalSkinRenderer.SkinTransform;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.AxisAlignedBB;

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

    private static void assertNormalSlabCenteredOnPlane(double anchor, double translation, double scale, double planeCoordinate, double thickness)
    {
        double low = anchor + translation;
        double high = low + scale;
        assertEquals(planeCoordinate - (thickness / 2.0D), low, EPSILON);
        assertEquals(planeCoordinate + (thickness / 2.0D), high, EPSILON);
    }
}
