package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.nio.ByteBuffer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

public class ClientPortalRendererTest {
    @Test
    public void incompatibleShadersRetainNativeScenesAndOnlyRetryAfterCooldown() {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        CameraRenderState camera = new CameraRenderState();
        RenderPass pass = mock(RenderPass.class);
        PortalScene scene = scene();

        try (MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class);
             MockedStatic<PortalShaderScope> shaders = mockStatic(PortalShaderScope.class)) {
            shaders.when(PortalShaderScope::rendering).thenThrow(new IllegalStateException("Shader binding unavailable"));
            renderer.prepare(camera, null);
            renderer.composite(pass);
            shaders.verify(PortalShaderScope::rendering, never());
            shaders.verify(PortalShaderScope::vertices, never());

            renderer.replaceScene(1, scene);
            renderer.prepare(camera, null);
            renderer.composite(pass);
            renderer.prepare(camera, null);
            renderer.composite(pass);
            renderer.prepare(camera, null);
            renderer.composite(pass);

            shaders.verify(PortalShaderScope::rendering, times(1));
            assertFalse(renderer.available(1));
            assertTrue(renderer.debugLine().contains("gpu=1/0"));
            assertTrue(renderer.debugLine().contains("unavailable=1"));
            verifyNoInteractions(pass);
            system.verify(() -> RenderSystem.setShaderFog(null), times(4));
            renderer.retryUnavailable(System.nanoTime() + ClientPortalRenderer.RETRY_NANOS + 1L);
            assertTrue(renderer.available(1));
            assertTrue(renderer.debugLine().contains("unavailable=1"));
            renderer.prepare(camera, null);
            shaders.verify(PortalShaderScope::rendering, times(2));
            assertFalse(renderer.available(1));
            assertTrue(renderer.debugLine().contains("gpu=1/0"));
            renderer.remove(1);
            renderer.prepare(camera, null);
            shaders.verify(PortalShaderScope::rendering, times(2));
            renderer.replaceScene(1, scene);
            renderer.featureFailed(1, new IllegalStateException("Feature extraction unavailable"));
            assertFalse(renderer.available(1));
            renderer.retryUnavailable(System.nanoTime() + ClientPortalRenderer.RETRY_NANOS + 1L);
            renderer.featuresReady(1);
            assertTrue(renderer.available(1));
            assertTrue(renderer.debugLine().contains("unavailable=0"));
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void failedDestinationDisposesItsInterruptedPipelineBeforeTheCooldownRetry() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalShaderRenderer shaders = mock(PortalShaderRenderer.class);
        Field pool = ClientPortalRenderer.class.getDeclaredField("shaderRenderer");
        pool.setAccessible(true);
        pool.set(renderer, shaders);
        try {
            renderer.replaceScene(1, scene());
            renderer.replaceScene(2, scene());
            clearInvocations(shaders);
            renderer.featureFailed(1, new IllegalStateException("Destination draw interrupted"));
            verify(shaders).remove(1);
            verify(shaders, never()).remove(2);
            assertFalse(renderer.available(1));
            assertTrue(renderer.available(2));
            renderer.featureFailed(1, new IllegalStateException("Repeated stale callback"));
            verify(shaders).remove(1);
            renderer.retryUnavailable(System.nanoTime() + ClientPortalRenderer.RETRY_NANOS + 1L);
            assertTrue(renderer.available(1));
            verify(shaders).remove(1);
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void readyShaderPrewarmInitializesCullingBeforeMaintainingUnseenSections() throws ReflectiveOperationException {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        PortalScene scene = scene();
        when(scene.environment()).thenReturn(PortalEnvironmentTest.environment(ClientViewEnvironment.Transform.IDENTITY));
        long behindCamera = SectionPos.asLong(0, 0, 1000);
        when(scene.sectionKeys()).thenReturn(LongArrayList.of(behindCamera));
        PortalShaderRenderer shaders = mock(PortalShaderRenderer.class);
        PortalShaderRenderer.Session session = mock(PortalShaderRenderer.Session.class);
        when(shaders.acquire(anyInt(), any(), anyInt(), anyInt())).thenReturn(session);
        when(session.ready()).thenReturn(true);
        when(session.materials()).thenReturn(PortalTerrainMaterials.VANILLA);
        renderer.replaceScene(1, scene);
        CameraRenderState camera = new CameraRenderState();
        camera.pos = Vec3.ZERO;
        camera.blockPos = BlockPos.ZERO;
        Field pool = ClientPortalRenderer.class.getDeclaredField("shaderRenderer");
        pool.setAccessible(true);
        pool.set(renderer, shaders);
        Field root = ClientPortalRenderer.class.getDeclaredField("rootCamera");
        root.setAccessible(true);
        root.set(renderer, camera);
        Field portals = ClientPortalRenderer.class.getDeclaredField("portals");
        portals.setAccessible(true);
        Object portal = ((Map<?, ?>) portals.get(renderer)).get(1);
        Class<?> dimensions = Class.forName(ClientPortalRenderer.class.getName() + "$RenderDimensions");
        Constructor<?> dimensionsConstructor = dimensions.getDeclaredConstructor(int.class, int.class, int.class);
        dimensionsConstructor.setAccessible(true);
        Method prewarm = ClientPortalRenderer.class.getDeclaredMethod("prewarmTree", portal.getClass(), Matrix4d.class, dimensions);
        prewarm.setAccessible(true);
        try {
            assertEquals(false, prewarm.invoke(renderer, portal, new Matrix4d(), dimensionsConstructor.newInstance(512, 256, 0)));
            assertTrue(renderer.available(1));
            verify(shaders, never()).remove(1);
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void reflectedCameraCullsInItsTransformedWorldSpace() {
        CameraRenderState root = new CameraRenderState();
        root.pos = new Vec3(778, 192, 12);
        root.projectionMatrix = new Matrix4f().setPerspective((float) Math.toRadians(70), 16.0f / 9.0f, 200, 0.05f, false);
        CameraRenderState reflected = ClientPortalRenderer.transformedCamera(root,
            new Matrix4d().m22(-1).m32(17), root.projectionMatrix);
        assertEquals(new Vec3(778, 192, 5), reflected.pos);
        assertTrue(reflected.cullFrustum.isVisible(new AABB(777, 191, 27, 779, 194, 29)));
        assertFalse(reflected.cullFrustum.isVisible(new AABB(777, 191, -28, 779, 194, -26)));
    }

    @Test
    public void terrainFogUsesCameraRelativePositionsAcrossSectionBoundaries() {
        CameraRenderState camera = new CameraRenderState();
        camera.pos = new Vec3(6.5, 125.62, 8.5);
        camera.viewRotationMatrix = new Matrix4f().rotateX((float) (Math.PI / 2.0));
        DynamicGpuData.Transform near = ClientPortalRenderer.terrainTransform(camera, SectionPos.asLong(0, 0, 0));
        DynamicGpuData.Transform far = ClientPortalRenderer.terrainTransform(camera, SectionPos.asLong(0, -1, 0));
        Vector3f nearBoundary = new Vector3f(7, 0, 9).add(near.modelOffset());
        Vector3f farBoundary = new Vector3f(7, 16, 9).add(far.modelOffset());
        assertEquals(new Vector3f(0.5f, -125.62f, 0.5f), nearBoundary);
        assertEquals(nearBoundary.x, farBoundary.x, 0.00002f);
        assertEquals(nearBoundary.y, farBoundary.y, 0.00002f);
        assertEquals(nearBoundary.z, farBoundary.z, 0.00002f);
        assertEquals(camera.viewRotationMatrix, near.modelView());
        Vector3f transformed = near.modelView().transformPosition(new Vector3f(nearBoundary));
        assertEquals(0.5f, transformed.x, 0.0001f);
        assertEquals(-0.5f, transformed.y, 0.0001f);
        assertEquals(-125.62f, transformed.z, 0.0001f);
    }

    @Test
    public void shaderTerrainSectionTranslationIsPartOfTheModelViewMatrix() {
        CameraRenderState camera = new CameraRenderState();
        camera.pos = new Vec3(520.5, 81, 14.5);
        camera.viewRotationMatrix = new Matrix4f().rotateY((float) Math.PI / 2);
        long section = SectionPos.asLong(32, 5, 0);
        DynamicGpuData.Transform vanilla = ClientPortalRenderer.terrainTransform(camera, section);
        DynamicGpuData.Transform shader = ClientPortalRenderer.shaderTerrainTransform(camera, section);
        Vector3f vertex = new Vector3f(8, 1, 4);
        Vector3f expected = vanilla.modelView().transformPosition(new Vector3f(vertex).add(vanilla.modelOffset()));
        Vector3f actual = shader.modelView().transformPosition(new Vector3f(vertex));
        assertEquals(expected.x, actual.x, 0.0001f);
        assertEquals(expected.y, actual.y, 0.0001f);
        assertEquals(expected.z, actual.z, 0.0001f);
        assertEquals(new Vector3f(), shader.modelOffset());
    }

    @Test
    public void rootCompositeUploadsDisabledClipPlaneAndNativePixelViewport() {
        GpuDevice device = mock(GpuDevice.class, RETURNS_DEEP_STUBS);
        GpuBuffer buffer = mock(GpuBuffer.class);
        when(device.createBuffer(any(), anyInt(), any(ByteBuffer.class))).thenAnswer(invocation -> {
            ByteBuffer data = invocation.getArgument(2);
            assertEquals(48, data.remaining());
            for (int component = 0; component < 4; component++) {
                assertEquals(0.0f, data.getFloat(component * Float.BYTES), 0.0f);
            }
            assertEquals(768.0f, data.getFloat(16), 0.0f);
            assertEquals(1080.0f, data.getFloat(20), 0.0f);
            assertEquals(576.0f, data.getFloat(24), 0.0f);
            assertEquals(0.0f, data.getFloat(28), 0.0f);
            assertEquals(device.getDeviceInfo().isZZeroToOne() ? 1.0f : 0.5f, data.getFloat(32), 0.0f);
            assertEquals(device.getDeviceInfo().isZZeroToOne() ? 0.0f : 0.5f, data.getFloat(36), 0.0f);
            return buffer;
        });
        try (MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class)) {
            system.when(RenderSystem::getDevice).thenReturn(device);
            assertSame(buffer, ClientPortalRenderer.compositeUniform(new PortalViewport(576, 0, 768, 1080)));
            when(device.getDeviceInfo().isZZeroToOne()).thenReturn(true);
            assertSame(buffer, ClientPortalRenderer.compositeUniform(new PortalViewport(576, 0, 768, 1080)));
        }
    }
    private static PortalScene scene() {
        PortalScene scene = mock(PortalScene.class);
        PortalGeometry aperture = new PortalGeometry();
        aperture.setArea(new AxisAlignedBB(0, 1.999, 0, 1.999, 0, 0.999));
        ClientPortalGeometry geometry = ClientPortalGeometry.fromPortal(new ClientPortalGeometry.Source(aperture,
            PortalFrame.canonical(Direction.S), true, false, 0, 0, 0, 0, 64, 0,
            ClientPortalGeometry.BLACKOUT_OFF, 0, ClientPortalGeometry.MASK_AIR_PROJECT,
            ProjectedBlockClaim.LightingPolicy.LOCAL, 0, ClientPortalGeometry.KIND_FRAME, 0, 0, List.of())).orElseThrow();
        when(scene.geometry()).thenReturn(geometry);
        return scene;
    }

}
