package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PortalViewSelectionTest extends MinecraftTestBase {
    @Test
    public void projectedChildMustIntersectItsAncestorOpening() {
        PortalSurface surface = surface(0, 0, ShapeDescriptor.FULL, 15L);
        for (boolean zeroToOne : List.of(false, true)) {
            PortalViewSelection.Camera camera = camera(new Vec3d(1.0D, 1.0D, 4.0D), zeroToOne, PortalViewSelection.Window.FULL);
            PortalViewSelection.Window bounds = PortalViewSelection.coverage(surface, camera);
            assertNotNull(bounds);
            PortalViewSelection.Window elsewhere = new PortalViewSelection.Window(0.8D, 0.8D, 1.0D, 1.0D);
            assertFalse(PortalViewSelection.visible(surface, camera(camera.eye(), zeroToOne, elsewhere)));
        }
    }

    @Test
    public void nearAndCoplanarEyesKeepConservativeCoverageInBothDepthConventions() {
        PortalSurface surface = surface(0, 0, ShapeDescriptor.FULL, 15L);
        for (boolean zeroToOne : List.of(false, true)) {
            for (double distance : List.of(0.0D, 1.0E-12D, 0.01D, 0.25D, 0.5D)) {
                Vec3d eye = surface.planePoint().add(new Vec3d(0.0D, 0.0D, distance));
                assertNotNull(PortalViewSelection.coverage(surface, camera(eye, zeroToOne, PortalViewSelection.Window.FULL)));
            }
        }
    }

    @Test
    public void transformedNativeCameraPreservesProjectedOpeningAcrossScaleRotationAndReflection() {
        PortalSurface surface = surface(0, 0, ShapeDescriptor.FULL, 15L);
        for (boolean zeroToOne : List.of(false, true)) {
            for (AxisPermutation axes : List.of(AxisPermutation.IDENTITY, AxisPermutation.of(Face.S, Face.U, Face.W),
                AxisPermutation.of(Face.W, Face.U, Face.S))) {
                Similarity transform = Similarity.of(OpticTransform.of(axes, 12.0D, -3.0D, 7.0D), 2.0D);
                PortalView view = mock(PortalView.class);
                when(view.surface()).thenReturn(surface);
                when(view.exit()).thenReturn(surface.through(transform));
                when(view.toDestination()).thenReturn(transform);
                PortalViewSelection.Camera outer = camera(new Vec3d(1.0D, 1.0D, 4.0D), zeroToOne, PortalViewSelection.Window.FULL);
                PortalViewSelection.Window expected = PortalViewSelection.coverage(surface, outer);
                PortalViewSelection.Camera inner = PortalViewSelection.through(outer, view);
                PortalViewSelection.Window actual = PortalViewSelection.coverage(view.exit(), inner);
                assertNotNull(actual);
                assertEquals(expected.minX(), actual.minX(), 1.0E-6D);
                assertEquals(expected.minY(), actual.minY(), 1.0E-6D);
                assertEquals(expected.maxX(), actual.maxX(), 1.0E-6D);
                assertEquals(expected.maxY(), actual.maxY(), 1.0E-6D);
            }
        }
    }

    @Test
    public void exitPlaneRejectsCoplanarAndClippedOpenings() {
        PortalSurface parent = surface(0, 0, ShapeDescriptor.FULL, 15L);
        PortalViewSelection.Camera root = camera(new Vec3d(1.0D, 1.0D, 4.0D), false, PortalViewSelection.Window.FULL);
        PortalViewSelection.Camera inner = new PortalViewSelection.Camera(root.eye(), root.view(), root.projection(), null,
            PortalViewSelection.Window.FULL, parent.through(Similarity.IDENTITY), false);
        assertFalse(PortalViewSelection.visible(parent, inner));
        assertFalse(PortalViewSelection.visible(surface(0, 2, ShapeDescriptor.FULL, 15L), inner));
        assertTrue(PortalViewSelection.visible(surface(0, -4, ShapeDescriptor.FULL, 15L), inner));
    }

    @Test
    public void shapeCoverageMatchesItsFullRenderedExtentAndSparseMasksRemainSparse() {
        PortalViewSelection.Camera camera = camera(new Vec3d(1.0D, 1.0D, 4.0D), false, PortalViewSelection.Window.FULL);
        PortalViewSelection.Window full = PortalViewSelection.coverage(surface(0, 0, ShapeDescriptor.FULL, 15L), camera);
        PortalViewSelection.Window shaped = PortalViewSelection.coverage(surface(0, 0, ShapeDescriptor.parse("circle"), 1L), camera);
        PortalViewSelection.Window sparse = PortalViewSelection.coverage(surface(0, 0, ShapeDescriptor.FULL, 1L), camera);
        assertEquals(full, shaped);
        assertNotNull(sparse);
        assertTrue(sparse.maxX() < full.maxX());
        assertTrue(sparse.maxY() < full.maxY());
    }

    @Test
    public void recursionBlockedChildCannotSuppressAnEligibleRootAtTheSameOpening() {
        ClientLevel level = mock(ClientLevel.class);
        PortalSurface surface = surface(0, 0, ShapeDescriptor.FULL, 15L);
        PortalView child = view(level, surface, 1);
        PortalView root = view(level, surface, 3);
        PortalViewSelection.Options options = new PortalViewSelection.Options(level, 1, 3, 128.0D, ignored -> true);
        assertEquals(List.of(root), PortalViewSelection.collect(List.of(child), List.of(root), options,
            camera(new Vec3d(1.0D, 1.0D, 4.0D), false, PortalViewSelection.Window.FULL)));
    }

    @Test
    public void ordinaryCeilingKeepsTheDestinationRayAndRejectsTheReturnBehindItsExit() {
        ClientLevel level = mock(ClientLevel.class);
        PortalSurface parentSurface = sceneSurface(200, 100, 20, Face.N, true, 8);
        PortalSurface ceilingSurface = sceneSurface(300, 108, 22, Face.D, true, 13);
        PortalSurface floorSurface = sceneSurface(300, 100, 22, Face.D, false, 13);
        PortalView parent = placedView(level, parentSurface, OpticTransform.of(AxisPermutation.IDENTITY, 100.0D, 0.0D, 0.0D));
        PortalView ceiling = placedView(level, ceilingSurface, OpticTransform.of(AxisPermutation.IDENTITY, 0.0D, -8.0D, 0.0D));
        PortalSurface returning = sceneSurface(300, 100, 20, Face.N, true, 8);
        Vec3d eye = new Vec3d(204.5D, 103.61999988555908D, 12.0D);
        Vec3d direction = new Vec3d(0.0D, 111.5D - eye.y(), 22.5D);
        for (boolean zeroToOne : List.of(false, true)) {
            Matrix4f rotation = new Matrix4f().lookAt(0.0F, 0.0F, 0.0F,
                0.0F, (float) direction.y(), (float) direction.z(), 0.0F, 1.0F, 0.0F);
            Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(70.0D), 16.0F / 9.0F,
                200.0F, 0.05F, zeroToOne);
            PortalViewSelection.Camera root = new PortalViewSelection.Camera(eye, rotation, projection, null,
                PortalViewSelection.Window.FULL, null, zeroToOne);
            PortalViewSelection.Camera outer = PortalViewSelection.through(root, parent);
            assertEquals(List.of(ceiling), PortalViewSelection.collect(List.of(ceiling), List.of(),
                new PortalViewSelection.Options(level, 1, 3, 128.0D, ignored -> true), outer));
            PortalViewSelection.Camera inner = PortalViewSelection.through(outer, ceiling);
            assertNotNull(inner.window());
            assertTrue(inner.window().minX() < 0.0D && inner.window().maxX() > 0.0D);
            assertTrue(inner.window().minY() < 0.0D && inner.window().maxY() > 0.0D);
            Vec3d cyan = new Vec3d(304.5D, 103.5D, 34.5D);
            assertTrue(inner.exit().signedDistance(cyan) > 0.0D);
            double rayFraction = (inner.exit().planePoint().y() - inner.eye().y()) / direction.y();
            assertTrue(inner.exit().contains(inner.eye().add(direction.multiply(rayFraction))));
            assertFalse(PortalViewSelection.visible(returning, inner));
            assertFalse(PortalViewSelection.visible(floorSurface, inner));
        }
    }

    @Test
    public void offsetPrimaryMirrorsHaveTwoVisiblePathsToTheWideNestedMirror() {
        ClientLevel level = mock(ClientLevel.class);
        PortalSurface first = mirrorSurface(40, 20, Face.N, false, 3);
        PortalSurface second = mirrorSurface(46, 23, Face.N, false, 3);
        PortalSurface smallNested = mirrorSurface(40, 44, Face.S, false, 3);
        PortalSurface wideNested = mirrorSurface(40, 44, Face.S, false, 33);
        for (boolean zeroToOne : List.of(false, true)) {
            PortalViewSelection.Camera camera = new PortalViewSelection.Camera(new Vec3d(41.5D, 71.62D, 30.5D), new Matrix4f(),
                new Matrix4f().setPerspective((float) Math.toRadians(70.0D), 1920.0F / 1080.0F, 200.0F, 0.05F, zeroToOne),
                null, PortalViewSelection.Window.FULL, null, zeroToOne);
            PortalViewSelection.Camera firstCamera = PortalViewSelection.through(camera,
                placedView(level, first, PortalSurface.mirrorTransform(first.geometry())));
            PortalViewSelection.Camera secondCamera = PortalViewSelection.through(camera,
                placedView(level, second, PortalSurface.mirrorTransform(second.geometry())));
            assertTrue(PortalViewSelection.visible(smallNested, firstCamera));
            assertFalse(PortalViewSelection.visible(smallNested, secondCamera));
            assertTrue(PortalViewSelection.visible(wideNested, firstCamera));
            assertTrue(PortalViewSelection.visible(wideNested, secondCamera));
        }
    }

    private static PortalSurface mirrorSurface(int x, int z, Face facing, boolean front, int width) {
        int bits = width * 3;
        long[] mask = bits < Long.SIZE ? new long[]{(1L << bits) - 1L} : new long[]{-1L, (1L << (bits - Long.SIZE)) - 1L};
        return PortalSurface.of(new ApertureDescriptor(x, 70, z, facing.ordinal(), front, 0, true, width, 3,
            mask, ShapeDescriptor.FULL, 0.0F, 0.0F, 0.0F, 80, 3, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of()));
    }

    private static PortalView placedView(ClientLevel level, PortalSurface surface, OpticTransform transform) {
        PortalView view = view(level, surface, 3);
        Similarity destination = Similarity.of(transform, 1.0D);
        when(view.toDestination()).thenReturn(destination);
        when(view.exit()).thenReturn(surface.through(destination));
        return view;
    }

    private static PortalSurface sceneSurface(int x, int y, int z, Face facing, boolean front, int height) {
        int remaining = 9 * height - Long.SIZE;
        return PortalSurface.of(new ApertureDescriptor(x, y, z, facing.ordinal(), front, 0, false, 9, height,
            new long[]{-1L, (1L << remaining) - 1L}, ShapeDescriptor.FULL, 0.0F, 0.0F, 0.0F,
            64, 3, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of()));
    }

    private static PortalView view(ClientLevel level, PortalSurface surface, int recursion) {
        PortalView view = mock(PortalView.class);
        when(view.source()).thenReturn(level);
        when(view.surface()).thenReturn(surface);
        when(view.recursion()).thenReturn(recursion);
        return view;
    }

    private static PortalViewSelection.Camera camera(Vec3d eye, boolean zeroToOne, PortalViewSelection.Window window) {
        return new PortalViewSelection.Camera(eye, new Matrix4f(),
            new Matrix4f().setPerspective((float) Math.toRadians(90.0D), 1.0F, 200.0F, 0.05F, zeroToOne), null, window, null, zeroToOne);
    }

    private static PortalSurface surface(int x, int z, ShapeDescriptor shape, long mask) {
        return PortalSurface.of(new ApertureDescriptor(x, 0, z, Face.S.ordinal(), true, 0, false, 2, 2, new long[]{mask}, shape,
            0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of()));
    }
}
