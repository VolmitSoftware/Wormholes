package art.arcane.wormholes.modded.client;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.stencil.PortalSurface;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.mixin.client.CameraPoseAccess;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class ClientCrossingViewTest extends MinecraftTestBase {
    private static final Vec3 EYE = new Vec3(0.5D, 1.62D, 0.3D);

    @Test
    public void bobbingStopsWithinABlockAndFadesBackBeyondTwo() {
        assertEquals(0.0D, ClientCrossingView.dampedBob(1.0D, 0.5D), 0.0D);
        assertEquals(0.5D, ClientCrossingView.dampedBob(1.0D, 1.5D), 1.0E-9D);
        assertEquals(0.1D, ClientCrossingView.dampedBob(0.0D, 5.0D), 1.0E-9D);
        assertEquals(0.05D, ClientCrossingView.dampedBob(0.0D, 1.5D), 1.0E-9D);
        assertEquals(1.0D, ClientCrossingView.dampedBob(1.0D, Double.POSITIVE_INFINITY), 0.0D);
    }

    @Test
    public void portalDistanceIsMeasuredToTheNearestPointOfTheOpening() {
        assertEquals(0.2D, ClientCrossingView.distance(ClientTravelTestFixtures.geometry(), EYE), 1.0E-9D);
        double edge = ClientTravelTestFixtures.geometry().apertureArea().getXb();
        assertEquals(Math.sqrt((4.0D - edge) * (4.0D - edge) + 0.2D * 0.2D),
            ClientCrossingView.distance(ClientTravelTestFixtures.geometry(), new Vec3(4.0D, 1.62D, 0.3D)), 1.0E-9D);
    }

    @Test
    public void aCameraBehindTheArmedPlaneRendersFromTheDestinationUntilTheFrameEnds() {
        try (Scene scene = new Scene(new Vec3(0.5D, 1.62D, 0.7D))) {
            scene.view.update(scene.camera, scene.tracker, List.of(scene.begin), null);
            assertTrue(scene.view.active());
            assertEquals(0.0D, scene.view.bobFactor(), 0.0D);
            Vec3d mapped = scene.begin.sourceToDestination().point(new Vec3d(0.5D, 1.62D, 0.7D));
            Angles.Look look = ClientTravelMotion.look(scene.begin.sourceToDestination().rigid(), 30.0F, 10.0F);
            verify(scene.pose).wormholes$position(new Vec3(mapped.x(), mapped.y(), mapped.z()));
            verify(scene.pose).wormholes$rotation(look.yaw(), look.pitch());
            scene.view.finish(scene.camera);
            assertFalse(scene.view.active());
            verify(scene.pose).wormholes$position(new Vec3(0.5D, 1.62D, 0.7D));
            verify(scene.pose).wormholes$rotation(30.0F, 10.0F);
        }
    }

    @Test
    public void aCameraOnTheNearSideKeepsTheSourceView() {
        try (Scene scene = new Scene(new Vec3(0.5D, 1.62D, 0.1D))) {
            scene.view.update(scene.camera, scene.tracker, List.of(scene.begin), null);
            assertFalse(scene.view.active());
            verify(scene.pose, never()).wormholes$position(any());
            verify(scene.pose, never()).wormholes$rotation(anyFloat(), anyFloat());
        }
    }

    @Test
    public void aCameraBehindTheReturnViewRendersTheDepartedWorldBeforeTheArrivalPortalIsArmed() {
        try (Scene scene = new Scene(new Vec3(0.5D, 1.62D, 0.7D))) {
            ClientLevel departed = mock(ClientLevel.class);
            Similarity back = scene.begin.sourceToDestination();
            PortalView returning = mock(PortalView.class);
            when(returning.key()).thenReturn("return");
            when(returning.destination()).thenReturn(departed);
            when(returning.surface()).thenReturn(PortalSurface.of(ClientTravelTestFixtures.geometry()));
            when(returning.toDestination()).thenReturn(back);
            scene.view.update(scene.camera, scene.tracker, List.of(), returning);
            assertTrue(scene.view.active());
            assertEquals(departed, scene.view.crossing().level());
            assertEquals("return", scene.view.crossing().key());
            Vec3d mapped = back.point(new Vec3d(0.5D, 1.62D, 0.7D));
            verify(scene.pose).wormholes$position(new Vec3(mapped.x(), mapped.y(), mapped.z()));
            scene.view.update(scene.camera, scene.tracker, List.of(), null);
            assertFalse(scene.view.active());
            assertNull(scene.view.crossing());
        }
    }

    private static final class Scene implements AutoCloseable {
        private final Minecraft minecraft = mock(Minecraft.class);
        private final LocalPlayer player = mock(LocalPlayer.class);
        private final ClientLevel level = mock(ClientLevel.class);
        private final Camera camera = mock(Camera.class, withSettings().extraInterfaces(CameraPoseAccess.class));
        private final CameraPoseAccess pose = (CameraPoseAccess) camera;
        private final DeltaTracker tracker = mock(DeltaTracker.class);
        private final TravelMessage.TravelBegin begin = ClientTravelMotionTest.begin(OpticTransform.of(AxisPermutation.of(Face.S, Face.U, Face.W), 0, 0, 0));
        private final ClientCrossingView view = new ClientCrossingView(new ResidentLevels(ignored -> { }, 512L << 20));
        private final MockedStatic<Minecraft> access;

        private Scene(Vec3 cameraPosition) {
            minecraft.player = player;
            minecraft.level = level;
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(player.getEyePosition(1.0F)).thenReturn(EYE);
            when(camera.isInitialized()).thenReturn(true);
            when(camera.entity()).thenReturn(player);
            when(camera.getCameraEntityPartialTicks(tracker)).thenReturn(1.0F);
            when(camera.position()).thenReturn(cameraPosition);
            when(camera.yRot()).thenReturn(30.0F);
            when(camera.xRot()).thenReturn(10.0F);
            when(camera.getViewRotationMatrix(any())).thenReturn(new Matrix4f());
            when(pose.wormholes$cullingProjection()).thenReturn(new Matrix4f());
            access = mockStatic(Minecraft.class);
            access.when(Minecraft::getInstance).thenReturn(minecraft);
        }

        @Override
        public void close() {
            access.close();
        }
    }
}
