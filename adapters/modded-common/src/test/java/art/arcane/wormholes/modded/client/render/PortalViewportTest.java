package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.render.client.ClientPortalAperture;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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

    private static CameraRenderState camera(boolean zeroToOne) {
        CameraRenderState camera = new CameraRenderState();
        camera.pos = new Vec3(100, 200, 300);
        camera.projectionMatrix = new Matrix4f().setPerspective((float) Math.toRadians(90), 1, 200, 0.05f, zeroToOne);
        camera.cullFrustum = new Frustum(camera.viewRotationMatrix, camera.projectionMatrix);
        camera.cullFrustum.prepare(camera.pos.x, camera.pos.y, camera.pos.z);
        return camera;
    }

    private static ClientPortalAperture aperture() {
        PortalGeometry aperture = new PortalGeometry();
        aperture.setArea(new AxisAlignedBB(0, 1.999, 0, 1.999, 0, 0.999));
        ClientPortalGeometry geometry = ClientPortalGeometry.fromPortal(new ClientPortalGeometry.Source(aperture,
            PortalFrame.canonical(Direction.S), true, false, 0, 0, 0, 0, 64, 0,
            ClientPortalGeometry.BLACKOUT_OFF, 0, ClientPortalGeometry.MASK_AIR_PROJECT,
            ProjectedBlockClaim.LightingPolicy.LOCAL, 0, ClientPortalGeometry.KIND_FRAME, 0, 0, List.of())).orElseThrow();
        return ClientPortalAperture.from(geometry);
    }
}
