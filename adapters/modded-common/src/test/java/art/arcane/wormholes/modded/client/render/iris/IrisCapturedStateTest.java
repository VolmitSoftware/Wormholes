package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.mixin.client.IrisPortalCapturedStateAccess;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class IrisCapturedStateTest {
    @Test
    public void startupNullMatricesAndFogAreRestoredWithoutPassingNullToTheCopyingProjectionSetter() {
        Scene scene = new Scene();
        IrisCapturedState snapshot = IrisCapturedState.capture(scene.state);
        assertNull(snapshot.view());
        assertNull(snapshot.projection());
        assertNull(snapshot.fog());
        scene.view = new Matrix4f().translation(3.0F, 4.0F, 5.0F);
        scene.projection = new Matrix4f().scaling(2.0F);
        scene.fog = new Vector3d(0.2D, 0.3D, 0.4D);
        snapshot.restore(scene.state);
        assertNull(scene.view);
        assertNull(scene.projection);
        assertNull(scene.fog);
        verify(scene.state, never()).setGbufferProjection(isNull());
    }

    @Test
    public void mixedStartupStateKeepsAnAbsentProjectionAndExactFogPrecision() {
        Scene scene = new Scene();
        Matrix4f original = new Matrix4f().rotationX(0.25F);
        Vector3d fog = new Vector3d(0.123456789012345D, 0.234567890123456D, 0.345678901234567D);
        scene.view = original;
        scene.fog = fog;
        IrisCapturedState snapshot = IrisCapturedState.capture(scene.state);
        original.scale(3.0F);
        fog.zero();
        scene.projection = new Matrix4f().scaling(2.0F);
        snapshot.restore(scene.state);
        assertEquals(new Matrix4f().rotationX(0.25F), scene.view);
        assertNotSame(original, scene.view);
        assertNull(scene.projection);
        assertEquals(0.123456789012345D, scene.fog.x, 0.0D);
        assertEquals(0.234567890123456D, scene.fog.y, 0.0D);
        assertEquals(0.345678901234567D, scene.fog.z, 0.0D);
        assertNotSame(fog, scene.fog);
    }

    @Test
    public void initializedCameraSnapshotsSurviveMutationOfTheNativeMatricesAndFog() {
        Scene scene = new Scene();
        Matrix4f view = new Matrix4f().translation(1.0F, 2.0F, 3.0F);
        Matrix4f projection = new Matrix4f().perspective(1.0F, 1.5F, 0.1F, 100.0F);
        Vector3d fog = new Vector3d(0.1D, 0.2D, 0.3D);
        scene.view = view;
        scene.projection = projection;
        scene.fog = fog;
        IrisCapturedState snapshot = IrisCapturedState.capture(scene.state);
        assertNotSame(view, snapshot.view());
        assertNotSame(projection, snapshot.projection());
        assertNotSame(fog, snapshot.fog());
        view.zero();
        projection.zero();
        fog.zero();
        snapshot.restore(scene.state);
        assertEquals(new Matrix4f().translation(1.0F, 2.0F, 3.0F), scene.view);
        assertEquals(new Matrix4f().perspective(1.0F, 1.5F, 0.1F, 100.0F), scene.projection);
        assertNotSame(snapshot.projection(), scene.projection);
        assertEquals(new Vector3d(0.1D, 0.2D, 0.3D), scene.fog);
        verify(scene.state).setTickDelta(0.25F);
        verify(scene.state).setRealTickDelta(0.5F);
        verify(scene.state).setCurrentEntity(12);
        verify(scene.state).setCurrentBlockEntity(13);
        verify(scene.state).setCurrentRenderedItem(14);
    }

    @Test
    public void nestedRestorationReturnsThroughPartiallyInitializedCameraStateToStartupAbsence() {
        Scene scene = new Scene();
        IrisCapturedState startup = IrisCapturedState.capture(scene.state);
        scene.view = new Matrix4f().translation(1.0F, 2.0F, 3.0F);
        scene.fog = new Vector3d(0.1D, 0.2D, 0.3D);
        IrisCapturedState parent = IrisCapturedState.capture(scene.state);
        scene.view = new Matrix4f().translation(7.0F, 8.0F, 9.0F);
        scene.projection = new Matrix4f().scaling(3.0F);
        scene.fog = new Vector3d(0.7D, 0.8D, 0.9D);
        parent.restore(scene.state);
        assertEquals(new Matrix4f().translation(1.0F, 2.0F, 3.0F), scene.view);
        assertNull(scene.projection);
        assertEquals(new Vector3d(0.1D, 0.2D, 0.3D), scene.fog);
        startup.restore(scene.state);
        assertNull(scene.view);
        assertNull(scene.projection);
        assertNull(scene.fog);
    }

    private static final class Scene {
        private final CapturedRenderingState state = mock(CapturedRenderingState.class,
            withSettings().extraInterfaces(IrisPortalCapturedStateAccess.class));
        private Matrix4fc view;
        private Matrix4fc projection;
        private Vector3d fog;

        private Scene() {
            when(state.getGbufferModelView()).thenAnswer(invocation -> view);
            when(state.getGbufferProjection()).thenAnswer(invocation -> projection);
            when(((IrisPortalCapturedStateAccess) state).wormholes$fog()).thenAnswer(invocation -> fog);
            when(state.getTickDelta()).thenReturn(0.25F);
            when(state.getRealTickDelta()).thenReturn(0.5F);
            when(state.getCurrentRenderedEntity()).thenReturn(12);
            when(state.getCurrentRenderedBlockEntity()).thenReturn(13);
            when(state.getCurrentRenderedItem()).thenReturn(14);
            doAnswer(invocation -> {
                view = invocation.getArgument(0);
                return null;
            }).when(state).setGbufferModelView(any());
            doAnswer(invocation -> {
                projection = new Matrix4f(invocation.<Matrix4f>getArgument(0));
                return null;
            }).when(state).setGbufferProjection(any());
            doAnswer(invocation -> {
                projection = invocation.getArgument(0);
                return null;
            }).when((IrisPortalCapturedStateAccess) state).wormholes$projection(any());
            doAnswer(invocation -> {
                fog = invocation.getArgument(0);
                return null;
            }).when((IrisPortalCapturedStateAccess) state).wormholes$fog(any());
        }
    }
}
