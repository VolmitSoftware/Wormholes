package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.transform.Affine;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import art.arcane.wormholes.portal.ApertureKind;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class ClientPortalRendererNestedTest extends MinecraftTestBase {
    @Test
    public void streamedFloorAndCeilingRemainEligibleUnderTheirNativeParent() {
        for (Face facing : List.of(Face.U, Face.D)) {
            ApertureDescriptor geometry = geometry(facing, 17);
            assertTrue(ClientPortalRenderer.streamedChild(geometry, 17, 1, 3, false));
            assertFalse(ClientPortalRenderer.streamedChild(geometry, 17, 1, 3, true));
            assertFalse(ClientPortalRenderer.streamedChild(geometry, 18, 1, 3, false));
            assertFalse(ClientPortalRenderer.streamedChild(geometry, 0, 1, 3, false));
            assertFalse(ClientPortalRenderer.streamedChild(geometry, 17, 3, 3, false));
        }
    }

    @Test
    public void residentFallbackValidatesTheSavedHomeStreamOwner() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        ClientLevel home = mock(ClientLevel.class);
        ClientLevel resident = mock(ClientLevel.class);
        PortalScene parent = mock(PortalScene.class);
        PortalScene child = mock(PortalScene.class);
        when(parent.geometry()).thenReturn(geometry(Face.S, 0));
        when(child.geometry()).thenReturn(geometry(Face.U, 17));
        when(parent.sourceLevel()).thenReturn(home);
        when(child.sourceLevel()).thenReturn(home);
        when(child.environment()).thenReturn(mock(EnvironmentState.class));
        renderer.replaceScene(17, parent);
        renderer.replaceScene(18, child);
        Field pipelines = ClientPortalRenderer.class.getDeclaredField("pipelines");
        pipelines.setAccessible(true);
        Object previous = pipelines.get(renderer);
        pipelines.set(renderer, mock(PortalPipelines.class));
        PortalStencilRenderer stencil = mock(PortalStencilRenderer.class);
        try (MockedStatic<PortalStencilRenderer> nativeRenderer = mockStatic(PortalStencilRenderer.class)) {
            nativeRenderer.when(PortalStencilRenderer::instance).thenReturn(stencil);
            assertTrue(renderer.nestedViews(17, home, 1, 3));
            assertFalse(renderer.nestedViews(17, resident, 1, 3));
            when(stencil.claimsChild(18)).thenReturn(true);
            assertFalse(renderer.nestedViews(17, home, 1, 3));
            when(stencil.claimsChild(18)).thenReturn(false);
            when(child.sourceExpired()).thenReturn(true);
            assertFalse(renderer.nestedViews(17, home, 1, 3));
            when(child.sourceExpired()).thenReturn(false);
            when(parent.sourceExpired()).thenReturn(true);
            assertFalse(renderer.nestedViews(17, home, 1, 3));
        } finally {
            pipelines.set(renderer, previous);
            renderer.clear();
        }
    }

    @Test
    public void streamedDescendantsKeepEveryAncestorCeiling() {
        int nativeCeiling = 3;
        int childCeiling = ClientPortalRenderer.branchCeiling(nativeCeiling, 1, 8);
        assertEquals(nativeCeiling, childCeiling);
        assertEquals(3, ClientPortalRenderer.branchCeiling(childCeiling, 2, 8));
        assertEquals(2, ClientPortalRenderer.branchCeiling(nativeCeiling, 1, 0));
        assertEquals(2, ClientPortalRenderer.branchCeiling(nativeCeiling, 1, 1));
        assertFalse(ClientPortalRenderer.streamedChild(geometry(Face.D, 23), 23, 2,
            ClientPortalRenderer.branchCeiling(nativeCeiling, 1, 0), false));
    }

    @Test
    public void nestedContentStartsInThePhysicalParentCameraSpace() {
        CameraRenderState parent = new CameraRenderState();
        parent.pos = new Vec3(104.0D, 77.0D, -52.0D);
        parent.blockPos = BlockPos.containing(parent.pos);
        parent.viewRotationMatrix = new Matrix4f().rotateX(0.4F).scale(0.5F);
        Matrix4f projection = new Matrix4f();
        assertSame(parent, ClientPortalRenderer.transformedCamera(parent, new Matrix4d(), projection));
        for (Face facing : List.of(Face.U, Face.D)) {
            ApertureDescriptor geometry = geometry(facing, 17);
            Matrix4d content = ClientPortalRenderer.contentSpace(new Matrix4d(), geometry, 0.5F);
            CameraRenderState child = ClientPortalRenderer.transformedCamera(parent, content, projection);
            Vector3d expected = new Matrix4d(content).invert().transformPosition(new Vector3d(parent.pos.x, parent.pos.y, parent.pos.z));
            assertEquals(expected.x, child.pos.x, 1.0E-9D);
            assertEquals(expected.y, child.pos.y, 1.0E-9D);
            assertEquals(expected.z, child.pos.z, 1.0E-9D);
            Matrix4f view = new Matrix4f(parent.viewRotationMatrix).mul(new Matrix4f(content).m30(0.0F).m31(0.0F).m32(0.0F));
            assertEquals(view, child.viewRotationMatrix);
        }
    }

    @Test
    public void physicalChildPlacementComposesAfterTheNativeParentSimilarity() {
        Similarity nativeToward = Similarity.of(OpticTransform.of(AxisPermutation.of(Face.E, Face.D, Face.S),
            120.0D, 85.0D, -30.0D), 2.0D);
        ApertureDescriptor geometry = geometry(Face.D, 17);
        Matrix4d localContent = ClientPortalRenderer.contentSpace(new Matrix4d(), geometry, 0.5F);
        Matrix4d homeContent = ClientPortalRenderer.contentSpace(PortalProjection.matrix(nativeToward.inverse()), geometry, 0.5F);
        Vector3d physicalPoint = new Vector3d(101.0D, 68.0D, -54.0D);
        Vector3d localPoint = localContent.transformPosition(new Vector3d(physicalPoint));
        Vec3d expected = nativeToward.inverse().point(new Vec3d(localPoint.x, localPoint.y, localPoint.z));
        Vector3d actual = homeContent.transformPosition(physicalPoint);
        assertEquals(expected.x(), actual.x, 1.0E-9D);
        assertEquals(expected.y(), actual.y, 1.0E-9D);
        assertEquals(expected.z(), actual.z, 1.0E-9D);
    }

    @Test
    public void affineFallbackCancelsTheWireTransformInsideThePhysicalParent() {
        Similarity nativeToward = Similarity.of(OpticTransform.of(AxisPermutation.of(Face.E, Face.D, Face.S),
            120.0D, 85.0D, -30.0D), 2.0D);
        Affine toward = Affine.translation(40.0D, 10.0D, -20.0D)
            .compose(Affine.shear(0.2D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D)).compose(Affine.scale(2.0D, 0.75D, 1.5D));
        OpticTransform wire = OpticTransform.of(AxisPermutation.IDENTITY, -32.0D, 4.0D, 12.0D);
        EnvironmentState environment = mock(EnvironmentState.class);
        when(environment.transform()).thenReturn(wire);
        ViewStreamMessage.PortalAffine sample = new ViewStreamMessage.PortalAffine(18, 4L, Affine.IDENTITY, toward, ShapeDescriptor.FULL);
        Vec3d destination = new Vec3d(42.0D, 16.0D, -11.0D);
        Vec3d streamed = wire.point(destination);
        Matrix4d content = ClientPortalRenderer.affineContentSpace(PortalProjection.matrix(nativeToward.inverse()), sample, environment);
        Vector3d actual = content.transformPosition(new Vector3d(streamed.x(), streamed.y(), streamed.z()));
        Vec3d expected = nativeToward.inverse().point(toward.inverse().point(destination));
        assertEquals(expected.x(), actual.x, 1.0E-9D);
        assertEquals(expected.y(), actual.y, 1.0E-9D);
        assertEquals(expected.z(), actual.z, 1.0E-9D);
    }

    private static ApertureDescriptor geometry(Face facing, int parent) {
        return new ApertureDescriptor(100, 64, -56, facing.ordinal(), true, 0, false, 3, 3, new long[] {511L},
            ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, parent, 11, List.of());
    }
}
