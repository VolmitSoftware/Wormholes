package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.portal.ApertureKind;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PortalViewportTest {
    @Test
    public void apertureBoundsRestrictRenderingToCoveredScreenTiles() {
        Matrix4d transform = new Matrix4d().scaling(0.25, 0.25, 1).translate(-1, -1, 0);
        PortalViewport viewport = PortalViewport.coverage(aperture(), transform, 1920, 1080, true);
        assertEquals(new PortalViewport(704, 384, 512, 320), viewport);
        assertTrue((long) viewport.width() * viewport.height() < 1920L * 1080L / 10);
        assertNull(PortalViewport.coverage(aperture(), new Matrix4d().translation(5, 0, 0), 1920, 1080, true));
    }

    @Test
    public void nestedCoverageIsLimitedToParentWithoutChangingPixelScale() {
        PortalViewport parent = new PortalViewport(640, 256, 512, 512);
        PortalViewport child = new PortalViewport(960, 320, 384, 256).intersect(parent);
        assertEquals(new PortalViewport(960, 320, 192, 256), child);
        assertNull(new PortalViewport(0, 0, 64, 64).intersect(parent));
    }

    @Test
    public void mixedResolutionNestedCoverageUsesNormalizedParentBounds() {
        PortalViewport parent = new PortalViewport(640, 256, 512, 512);
        PortalViewport scaled = parent.rescale(1920, 1080, 960, 540);
        assertEquals(new PortalViewport(320, 128, 256, 256), scaled);
        assertEquals(new PortalViewport(480, 160, 96, 128),
            new PortalViewport(480, 160, 192, 128).intersect(scaled));
        assertNull(new PortalViewport(0, 0, 64, 64).intersect(scaled));
        assertEquals(parent, scaled.rescale(960, 540, 1920, 1080));
        assertSame(parent, parent.rescale(1920, 1080, 1920, 1080));
    }

    @Test
    public void fractionalEdgesRoundOutwardAndScaleEachAxisIndependently() {
        PortalViewport viewport = new PortalViewport(1, 2, 1, 1);
        assertEquals(new PortalViewport(0, 1, 2, 1), viewport.rescale(3, 4, 2, 2));
        assertEquals(new PortalViewport(2, 3, 3, 3), viewport.rescale(3, 4, 7, 7));
        assertEquals(new PortalViewport(0, 0, 1920, 1080),
            new PortalViewport(0, 0, 960, 540).rescale(960, 540, 1920, 1080));
    }

    @Test
    public void rescaleClipsOffscreenBoundsAndAvoidsIntegerOverflow() {
        assertEquals(new PortalViewport(0, 0, 50, 25),
            new PortalViewport(-10, -10, 110, 60).rescale(100, 100, 50, 50));
        assertNull(new PortalViewport(100, 0, 10, 10).rescale(100, 100, 50, 50));
        assertNull(new PortalViewport(0, 0, 0, 10).rescale(100, 100, 50, 50));
        assertEquals(new PortalViewport(1, 1, Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1),
            new PortalViewport(1, 1, Integer.MAX_VALUE, Integer.MAX_VALUE)
                .rescale(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () ->
            new PortalViewport(0, 0, 10, 10).rescale(0, 100, 50, 50));
    }

    @Test
    public void apertureFrustumRejectsOutsideTerrainAndRetainsIntersectingEdges() {
        for (boolean zeroToOne : new boolean[] {false, true}) {
            CameraRenderState camera = camera(zeroToOne);
            Matrix4f projection = new Matrix4f(camera.projectionMatrix);
            Matrix4f view = new Matrix4f(camera.viewRotationMatrix);
            Frustum original = camera.cullFrustum;
            AABB outside = new AABB(114, 199, 279, 116, 201, 281);
            assertTrue(original.isVisible(outside));

            Frustum cropped = new PortalViewport(256, 256, 512, 512).frustum(camera, projection, 1024, 1024);

            assertFalse(cropped.isVisible(outside));
            assertFalse(cropped.isVisible(new AABB(99, 214, 279, 101, 216, 281)));
            assertTrue(cropped.isVisible(new AABB(99, 199, 279, 101, 201, 281)));
            assertTrue(cropped.isVisible(new AABB(109.9, 199, 279.9, 110.1, 201, 280.1)));
            assertTrue(cropped.isVisible(new AABB(89.9, 199, 279.9, 90.1, 201, 280.1)));
            assertTrue(cropped.isVisible(new AABB(99, 209.9, 279.9, 101, 210.1, 280.1)));
            assertTrue(cropped.isVisible(new AABB(99, 189.9, 279.9, 101, 190.1, 280.1)));
            assertEquals(camera.projectionMatrix, projection);
            assertEquals(view, camera.viewRotationMatrix);
            assertSame(original, camera.cullFrustum);
            assertTrue(original.isVisible(outside));
        }
    }

    @Test
    public void fullViewportPreservesCameraFrustum() {
        CameraRenderState camera = camera(true);
        Frustum full = new PortalViewport(0, 0, 1024, 1024).frustum(camera, camera.projectionMatrix, 1024, 1024);
        for (int x = 60; x <= 140; x += 4) {
            AABB box = new AABB(x, 199, 279, x + 2, 201, 281);
            assertEquals(camera.cullFrustum.isVisible(box), full.isVisible(box));
        }
    }

    @Test
    public void nestedMirroredFrustumUsesIntersectedScreenSpace() {
        CameraRenderState root = camera(true);
        Matrix4d mirror = new Matrix4d().m22(-1).m32(600);
        CameraRenderState reflected = ClientPortalRenderer.transformedCamera(root, mirror, root.projectionMatrix);
        PortalViewport parent = new PortalViewport(768, 256, 256, 512);
        PortalViewport child = new PortalViewport(512, 384, 512, 256).intersect(parent);
        assertEquals(new PortalViewport(768, 384, 256, 256), child);
        Frustum cropped = child.frustum(reflected, root.projectionMatrix, 1024, 1024);

        assertTrue(cropped.isVisible(new AABB(114, 199, 319, 116, 201, 321)));
        assertTrue(cropped.isVisible(new AABB(109.9, 199, 319.9, 110.1, 201, 320.1)));
        assertFalse(cropped.isVisible(new AABB(99, 199, 319, 101, 201, 321)));
        assertFalse(cropped.isVisible(new AABB(114, 208, 319, 116, 210, 321)));
        assertFalse(cropped.isVisible(new AABB(114, 199, 279, 116, 201, 281)));
        assertTrue(root.cullFrustum.isVisible(new AABB(114, 199, 279, 116, 201, 281)));
    }

    @Test
    public void shapedCoverageSpansTheWholeApertureRectangle() {
        boolean[] center = new boolean[49];
        center[24] = true;
        boolean[] open = new boolean[49];
        Arrays.fill(open, true);
        Matrix4d transform = new Matrix4d().scaling(0.1, 0.1, 1).translate(-3.5, -3.5, 0);
        PortalViewport shaped = PortalViewport.coverage(square(ShapeDescriptor.parse("circle"), center), transform, 1920, 1080, true);
        PortalViewport whole = PortalViewport.coverage(square(ShapeDescriptor.FULL, open), transform, 1920, 1080, true);
        PortalViewport cell = PortalViewport.coverage(square(ShapeDescriptor.FULL, center), transform, 1920, 1080, true);
        assertEquals(whole, shaped);
        assertTrue((long) cell.width() * cell.height() < (long) whole.width() * whole.height());
    }

    private static CameraRenderState camera(boolean zeroToOne) {
        CameraRenderState camera = new CameraRenderState();
        camera.pos = new Vec3(100, 200, 300);
        camera.projectionMatrix = new Matrix4f().setPerspective((float) Math.toRadians(90), 1, 200, 0.05f, zeroToOne);
        camera.cullFrustum = new Frustum(camera.viewRotationMatrix, camera.projectionMatrix);
        camera.cullFrustum.prepare(camera.pos.x, camera.pos.y, camera.pos.z);
        return camera;
    }

    private static AperturePolygon square(ShapeDescriptor shape, boolean[] open) {
        return AperturePolygon.from(new ApertureDescriptor(0, 0, 0, Face.S.ordinal(), true, 0, false, 7, 7,
            ApertureDescriptor.apertureMask(7, 7, open), shape, 0.0F, 0.0F, 1.0F, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME,
            0.0D, 0, 1L, List.of()));
    }

    private static AperturePolygon aperture() {
        ApertureCells aperture = new ApertureCells();
        aperture.setArea(new Box(0, 1.999, 0, 1.999, 0, 0.999));
        ApertureDescriptor geometry = ApertureDescriptor.fromPortal(new ApertureDescriptor.Source(aperture,
            Frame.canonical(Face.S), true, false, 0, 0, 0, 0, 64, 0,
            ApertureDescriptor.BLACKOUT_OFF, 0, ApertureDescriptor.MASK_AIR_PROJECT,
            BlockClaim.LightingPolicy.LOCAL, 0, ApertureKind.FRAME, 0.0D, 0, 0, ShapeDescriptor.FULL, List.of())).orElseThrow();
        return AperturePolygon.from(geometry);
    }
}
