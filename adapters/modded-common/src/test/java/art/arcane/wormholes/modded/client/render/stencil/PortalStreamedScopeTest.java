package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.portal.ApertureKind;
import art.arcane.wormholes.modded.client.render.PortalGpuMesh;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PortalStreamedScopeTest extends MinecraftTestBase {
    @Test
    public void shadedFailureRestoresNativeDepthAndCamera() throws ReflectiveOperationException {
        checkShadedScope(true);
    }

    @Test
    public void shadedLayerUsesNativeParentDepth() throws ReflectiveOperationException {
        checkShadedScope(false);
    }

    @Test
    public void duplicateSelectionKeepsDifferentlyPlacedAndReversedOpenings() {
        ApertureDescriptor geometry = new ApertureDescriptor(100, 64, -56, Face.U.ordinal(), true, 0, false, 3, 3,
            new long[] {511L}, ShapeDescriptor.FULL, 0, 0, 1, 64, 3, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 17, 11, List.of());
        PortalSurface surface = PortalSurface.of(geometry);
        PortalSurface shifted = surface.through(Similarity.of(OpticTransform.of(AxisPermutation.IDENTITY, 24.0D, 0.0D, 0.0D), 1.0D))
            .through(Similarity.IDENTITY);
        assertTrue(surface.sameOpening(PortalSurface.of(geometry)));
        assertFalse(surface.sameOpening(shifted));
        assertFalse(surface.sameOpening(surface.through(Similarity.IDENTITY)));
    }

    private static void checkShadedScope(boolean failure) throws ReflectiveOperationException {
        PortalStencilRenderer renderer = PortalStencilRenderer.instance();
        Field deferredField = field("deferred");
        Field cameraField = field("shadedCamera");
        DeferredLayers previousDeferred = (DeferredLayers) deferredField.get(renderer);
        Camera previousCamera = (Camera) cameraField.get(renderer);
        DeferredLayers deferred = mock(DeferredLayers.class);
        Camera outerCamera = mock(Camera.class);
        Camera childCamera = mock(Camera.class);
        PortalGpuMesh aperture = mock(PortalGpuMesh.class);
        Matrix4f view = new Matrix4f();
        StencilLayers layers = (StencilLayers) field("layers").get(renderer);
        layers.beginFrame(12);
        layers.enter(false);
        layers.enter(false);
        Minecraft minecraft = mock(Minecraft.class);
        GameRenderer game = mock(GameRenderer.class);
        RenderTarget main = mock(RenderTarget.class);
        Field gameRenderer = Minecraft.class.getDeclaredField("gameRenderer");
        gameRenderer.setAccessible(true);
        gameRenderer.set(minecraft, game);
        when(game.mainRenderTarget()).thenReturn(main);
        deferredField.set(renderer, deferred);
        cameraField.set(renderer, outerCamera);
        try (MockedStatic<Minecraft> client = mockStatic(Minecraft.class);
             MockedStatic<PortalStencil> stencil = mockStatic(PortalStencil.class)) {
            client.when(Minecraft::getInstance).thenReturn(minecraft);
            try {
                renderer.shadedView(aperture, false, view, childCamera, () -> {
                    assertEquals(3, layers.depth());
                    assertSame(childCamera, renderer.camera());
                    if (failure) {
                        throw new IllegalStateException("render failed");
                    }
                });
                if (failure) {
                    fail("Expected rendering failure");
                }
            } catch (IllegalStateException expected) {
                if (!failure) {
                    throw expected;
                }
                assertEquals("render failed", expected.getMessage());
            }
            assertEquals(2, layers.depth());
            assertSame(outerCamera, renderer.camera());
            verify(deferred).mark(2, aperture, false, view, (Matrix4f) field("projection").get(renderer), null);
            verify(deferred).forget(3);
            if (failure) {
                verify(deferred, never()).composite(any(), anyInt(), anyInt());
            } else {
                verify(deferred).composite(main, 3, 2);
                verify(deferred).writeDepth(2, aperture, false, view, (Matrix4f) field("projection").get(renderer), null);
            }
            stencil.verify(() -> PortalStencil.restore(2));
        } finally {
            layers.exit();
            layers.exit();
            deferredField.set(renderer, previousDeferred);
            cameraField.set(renderer, previousCamera);
        }
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = PortalStencilRenderer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
