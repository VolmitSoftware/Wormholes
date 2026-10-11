package art.arcane.wormholes.modded.client.render.dh;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.iris.IrisClipPlanes;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.mixin.client.DhPortalClientApiAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalDhCompatAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalLodEventsAccess;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.rendering.DhRenderState;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.render.CameraZoom;
import com.seibel.distanthorizons.core.render.DhApiRenderProxy;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.TimerUtil;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.world.IDhClientWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Vector4d;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.Timer;
import java.util.TimerTask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DhMainRenderContextTest extends MinecraftTestBase {
    @Test
    public void mainCorrectsThePlayerWorldAndRetainsOfficialMatricesAndIrisState() {
        try (Scene scene = new Scene()) {
            DhMat4f modelView = new DhMat4f(new Matrix4f().rotationY(0.4F));
            DhMat4f projection = new DhMat4f(new Matrix4f().perspective(1.2F, 1.5F, 0.05F, 2048.0F));
            scene.state.mcModelViewMatrix = modelView;
            scene.state.mcProjectionMatrix = projection;
            scene.state.clientLevelWrapper = scene.home;
            scene.state.partialTickTime = 0.4F;
            scene.state.vanillaFogEnabled = true;
            scene.parameters.mcProjectionMatrix.m00 = 3.0F;
            scene.parameters.apiCopy.dhInverseMvmProjectionMatrix.m33 = 2.0F;
            CameraZoom.INSTANCE.magnification = 2.0D;
            CameraZoom.INSTANCE.coneTanHalfAngle = 0.3D;
            CameraZoom.INSTANCE.lookDirectionX = 0.6D;
            CameraZoom.INSTANCE.lookDirectionZ = 0.8D;
            DhApiRenderProxy.INSTANCE.setDeferTransparentRendering(true);

            try (DhMainRenderContext scope = DhMainRenderContext.open(scene.destination)) {
                assertSame(scene.home, scene.state.clientLevelWrapper);
                DhPortalRendering.prepareLods();
                assertSame(scene.remote, scene.state.clientLevelWrapper);
                assertSame(modelView, scene.state.mcModelViewMatrix);
                assertSame(projection, scene.state.mcProjectionMatrix);
                assertTrue(scene.translucent);
                assertNull(DhClipPrograms.plane());
                scene.state.clientLevelWrapper = scene.home;
                DhPortalRendering.prepareLods();
                assertSame(scene.remote, scene.state.clientLevelWrapper);

                scene.state.mcModelViewMatrix = new DhMat4f();
                scene.state.mcProjectionMatrix = new DhMat4f();
                scene.state.partialTickTime = 0.9F;
                scene.state.vanillaFogEnabled = false;
                scene.parameters.mcProjectionMatrix.m00 = 5.0F;
                scene.parameters.apiCopy.dhInverseMvmProjectionMatrix.m33 = 7.0F;
                CameraZoom.INSTANCE.magnification = 4.0D;
                CameraZoom.INSTANCE.coneTanHalfAngle = 0.5D;
                CameraZoom.INSTANCE.lookDirectionX = 1.0D;
                CameraZoom.INSTANCE.lookDirectionZ = 0.0D;
                DhApiRenderProxy.INSTANCE.setDeferTransparentRendering(false);
                scene.translucent = false;
                scene.width = 640;
                scene.height = 480;
                scene.incompatible = false;
            }

            assertSame(scene.home, scene.state.clientLevelWrapper);
            assertSame(modelView, scene.state.mcModelViewMatrix);
            assertSame(projection, scene.state.mcProjectionMatrix);
            assertEquals(0.4F, scene.state.partialTickTime, 0.0F);
            assertTrue(scene.state.vanillaFogEnabled);
            assertEquals(3.0F, scene.parameters.mcProjectionMatrix.m00, 0.0F);
            assertEquals(2.0F, scene.parameters.apiCopy.dhInverseMvmProjectionMatrix.m33, 0.0F);
            assertEquals(2.0D, CameraZoom.INSTANCE.magnification, 0.0D);
            assertEquals(0.3D, CameraZoom.INSTANCE.coneTanHalfAngle, 0.0D);
            assertEquals(0.6D, CameraZoom.INSTANCE.lookDirectionX, 0.0D);
            assertEquals(0.8D, CameraZoom.INSTANCE.lookDirectionZ, 0.0D);
            assertTrue(DhApiRenderProxy.INSTANCE.getDeferTransparentRendering());
            assertTrue(scene.translucent);
            assertEquals(1920, scene.width);
            assertEquals(1080, scene.height);
            assertTrue(scene.incompatible);
            DhPortalRendering.prepareLods();
            assertSame(scene.home, scene.state.clientLevelWrapper);
        }
    }

    @Test
    public void nestedMainScopesRestoreTheirParentOnFailureAndRejectOutOfOrderClose() {
        try (Scene scene = new Scene(); DhMainRenderContext outer = DhMainRenderContext.open(scene.destination)) {
            DhPortalRendering.prepareLods();
            assertSame(scene.remote, scene.state.clientLevelWrapper);
            assertThrows(IllegalStateException.class, () -> {
                try (DhMainRenderContext inner = DhMainRenderContext.open(scene.source)) {
                    DhPortalRendering.prepareLods();
                    assertSame(scene.home, scene.state.clientLevelWrapper);
                    assertThrows(IllegalStateException.class, outer::close);
                    throw new IllegalStateException("render failure");
                }
            });
            assertSame(scene.remote, scene.state.clientLevelWrapper);
            DhPortalRendering.prepareLods();
            assertSame(scene.remote, scene.state.clientLevelWrapper);
        }
    }

    @Test
    public void aNestedPortalKeepsPriorityThenRestoresTheFullMainRender() {
        try (Scene scene = new Scene(); MockedStatic<IrisClipPlanes> clipping = mockStatic(IrisClipPlanes.class);
             DhMainRenderContext main = DhMainRenderContext.open(scene.destination)) {
            clipping.when(IrisClipPlanes::capture).thenReturn(mock(IrisClipPlanes.State.class));
            DhPortalRendering.prepareLods();
            DhMat4f modelView = scene.state.mcModelViewMatrix;
            DhMat4f projection = scene.state.mcProjectionMatrix;
            PortalView view = mock(PortalView.class);
            when(view.source()).thenReturn(scene.destination);
            when(view.destination()).thenReturn(scene.source);
            when(scene.destination.dimension()).thenReturn(Level.NETHER);
            when(scene.source.dimension()).thenReturn(Level.OVERWORLD);

            try (DhPortalRendering.Scope portal = DhPortalRenderContext.open(view, scene.source, new Matrix4f().rotationX(0.7F),
                new Matrix4f().perspective(1.0F, 1.5F, 0.05F, 1024.0F), 0.8F, new Vector4d(0.0D, 0.0D, 1.0D, -5.0D),
                new Vec3d(2.0D, 64.0D, 6.0D))) {
                assertFalse(scene.translucent);
                scene.state.clientLevelWrapper = scene.remote;
                scene.state.mcModelViewMatrix = modelView;
                scene.state.mcProjectionMatrix = projection;
                DhPortalRendering.prepareLods();
                assertSame(scene.home, scene.state.clientLevelWrapper);
                assertEquals(new DhMat4f(new Matrix4f().rotationX(0.7F)), scene.state.mcModelViewMatrix);
                assertEquals(1.0F, DhClipPrograms.plane().w(), 0.0F);
            }

            assertSame(scene.remote, scene.state.clientLevelWrapper);
            assertSame(modelView, scene.state.mcModelViewMatrix);
            assertSame(projection, scene.state.mcProjectionMatrix);
            assertTrue(scene.translucent);
            assertNull(DhClipPrograms.plane());
            DhPortalRendering.prepareLods();
            assertSame(scene.remote, scene.state.clientLevelWrapper);
        }
    }

    @Test
    public void virtualMainReusesItsOwnTerrainSelectionAndProviderLifecycle() {
        try (Scene scene = new Scene()) {
            FullDataSourceProviderV2 provider = mock(FullDataSourceProviderV2.class);
            FullDataSourceProviderV2 homeProvider = mock(FullDataSourceProviderV2.class);
            IDhClientWorld world = mock(IDhClientWorld.class);
            IDhClientLevel level = mock(IDhClientLevel.class);
            IDhClientLevel homeLevel = mock(IDhClientLevel.class);
            when(level.getFullDataProvider()).thenReturn(provider);
            when(homeLevel.getFullDataProvider()).thenReturn(homeProvider);
            scene.parameters.dhClientWorld = world;
            scene.parameters.dhClientLevel = level;
            scene.parameters.exactCameraPosition = new DhVec3d(80000.5D, 70.0D, -160000.25D);
            PortalView view = mock(PortalView.class);
            when(view.source()).thenReturn(scene.source);
            when(view.destination()).thenReturn(scene.destination);
            when(scene.destination.dimension()).thenReturn(Level.NETHER);
            when(scene.source.dimension()).thenReturn(Level.OVERWORLD);
            DhPortalLodViews.Path main = DhPortalLodViews.mainPath(scene.destination);
            assertEquals(main, DhPortalLodViews.mainPath(scene.destination));
            assertNotEquals(main, DhPortalLodViews.mainPath(scene.source));
            assertNotEquals(main, DhPortalLodViews.path(null, view));

            try {
                try (DhMainRenderContext scope = DhMainRenderContext.open(scene.destination)) {
                    DhPortalRendering.prepareRenderParams();
                    DhPortalRendering.prepareRenderParams();
                }
                scene.parameters.exactCameraPosition = new DhVec3d(80016.5D, 70.0D, -160000.25D);
                assertEquals(main, DhPortalLodViews.mainPath(scene.destination));
                try (DhMainRenderContext scope = DhMainRenderContext.open(scene.destination)) {
                    DhPortalRendering.prepareRenderParams();
                }
                verify(provider, times(1)).addDataSourceUpdateListener(any());
                scene.parameters.dhClientLevel = homeLevel;
                try (DhMainRenderContext scope = DhMainRenderContext.open(scene.source)) {
                    DhPortalRendering.prepareRenderParams();
                }
                verify(homeProvider, times(1)).addDataSourceUpdateListener(any());
                verify(provider, never()).removeDataSourceUpdateListener(any());
                scene.parameters.dhClientLevel = level;
                try (DhMainRenderContext scope = DhMainRenderContext.open(scene.destination)) {
                    DhPortalRendering.prepareRenderParams();
                }
                verify(provider, times(1)).addDataSourceUpdateListener(any());
                DhPortalLodViews.closeProvider(provider);
                verify(provider, times(1)).removeDataSourceUpdateListener(any());
                try (DhMainRenderContext scope = DhMainRenderContext.open(scene.destination)) {
                    DhPortalRendering.prepareRenderParams();
                }
                verify(provider, times(2)).addDataSourceUpdateListener(any());
            } finally {
                DhPortalLodViews.clear();
            }
            verify(provider, times(2)).removeDataSourceUpdateListener(any());
            verify(homeProvider, times(1)).removeDataSourceUpdateListener(any());
        }
    }

    private static final class Scene implements AutoCloseable {
        private final ClientLevel source = mock(ClientLevel.class);
        private final ClientLevel destination = mock(ClientLevel.class);
        private final IClientLevelWrapper home = mock(IClientLevelWrapper.class);
        private final IClientLevelWrapper remote = mock(IClientLevelWrapper.class);
        private final RenderParams parameters = new RenderParams();
        private final DhRenderState state = ClientApi.RENDER_STATE;
        private final MockedStatic<TimerUtil> timers = mockStatic(TimerUtil.class);
        private final MockedStatic<ClientLevelWrapper> wrappers;
        private final MockedStatic<DhPortalClientApiAccess> access = mockStatic(DhPortalClientApiAccess.class);
        private final MockedStatic<IrisPortalLodEventsAccess> events = mockStatic(IrisPortalLodEventsAccess.class);
        private final MockedStatic<IrisPortalDhCompatAccess> compat = mockStatic(IrisPortalDhCompatAccess.class);
        private final DhRenderSnapshot original;
        private boolean translucent = true;
        private int width = 1920;
        private int height = 1080;
        private boolean incompatible = true;

        private Scene() {
            when(source.dimension()).thenReturn(Level.OVERWORLD);
            when(destination.dimension()).thenReturn(Level.NETHER);
            timers.when(() -> TimerUtil.CreateTimer(anyString())).thenReturn(mock(Timer.class));
            timers.when(() -> TimerUtil.createTimerTask(any())).thenReturn(mock(TimerTask.class));
            wrappers = mockStatic(ClientLevelWrapper.class);
            wrappers.when(() -> ClientLevelWrapper.getWrapper(source)).thenReturn(home);
            wrappers.when(() -> ClientLevelWrapper.getWrapper(destination)).thenReturn(remote);
            access.when(DhPortalClientApiAccess::wormholes$renderParams).thenReturn(parameters);
            events.when(IrisPortalLodEventsAccess::wormholes$atTranslucent).thenAnswer(ignored -> translucent);
            events.when(() -> IrisPortalLodEventsAccess.wormholes$atTranslucent(anyBoolean())).thenAnswer(call -> {
                translucent = call.getArgument(0);
                return null;
            });
            events.when(IrisPortalLodEventsAccess::wormholes$textureWidth).thenAnswer(ignored -> width);
            events.when(() -> IrisPortalLodEventsAccess.wormholes$textureWidth(anyInt())).thenAnswer(call -> {
                width = call.getArgument(0);
                return null;
            });
            events.when(IrisPortalLodEventsAccess::wormholes$textureHeight).thenAnswer(ignored -> height);
            events.when(() -> IrisPortalLodEventsAccess.wormholes$textureHeight(anyInt())).thenAnswer(call -> {
                height = call.getArgument(0);
                return null;
            });
            compat.when(IrisPortalDhCompatAccess::wormholes$lastIncompatible).thenAnswer(ignored -> incompatible);
            compat.when(() -> IrisPortalDhCompatAccess.wormholes$lastIncompatible(anyBoolean())).thenAnswer(call -> {
                incompatible = call.getArgument(0);
                return null;
            });
            original = new DhRenderSnapshot();
        }

        @Override
        public void close() {
            try {
                original.restore();
            } finally {
                compat.close();
                events.close();
                access.close();
                wrappers.close();
                timers.close();
            }
        }
    }
}
