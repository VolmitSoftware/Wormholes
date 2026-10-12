package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.stencil.PortalSurface;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.gravity.GravityHolder;
import art.arcane.wormholes.modded.mixin.client.CameraPoseAccess;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
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
            ArgumentCaptor<Float> yaw = ArgumentCaptor.forClass(Float.class);
            ArgumentCaptor<Float> pitch = ArgumentCaptor.forClass(Float.class);
            verify(scene.pose).wormholes$rotation(yaw.capture(), pitch.capture());
            assertEquals(0.0F, Angles.unwrap(yaw.getValue() - look.yaw(), 0.0F), 1.0E-4F);
            assertEquals(look.pitch(), pitch.getValue(), 1.0E-4F);
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
    public void firstAndThirdPersonTransfersKeepTheWholeCameraBasisAtOrdinaryAndPoleLooks() {
        OpticTransform toward = OpticTransform.between(Frame.canonical(Face.N), new Vec3d(0, 0, 0), Frame.canonical(Face.U), new Vec3d(0, 0, 0));
        TravelMessage.TravelBegin arm = ClientTravelMotionTest.begin(toward.inverse());
        for (boolean detached : new boolean[] {false, true}) {
            for (float pitch : new float[] {-90.0F, -89.9F, 10.0F, 89.9F, 90.0F}) {
                try (Scene scene = new Scene(new Vec3(0.5D, 1.62D, 0.7D))) {
                    when(scene.camera.isDetached()).thenReturn(detached);
                    when(scene.camera.xRot()).thenReturn(pitch);
                    scene.rotation.rotationYXZ((float) Math.toRadians(150.0D), (float) Math.toRadians(-pitch), 0.0F);
                    scene.view.update(scene.camera, scene.tracker, List.of(arm), null);

                    assertTrue(scene.view.active());
                    Vec3d forward = toward.vector(Angles.direction(30.0F, pitch));
                    Vec3d up = toward.vector(Angles.direction(30.0F, pitch - 90.0F));
                    assertVector(forward, new Vector3f(0.0F, 0.0F, -1.0F).rotate(scene.rotation));
                    assertVector(up, new Vector3f(0.0F, 1.0F, 0.0F).rotate(scene.rotation));
                    scene.view.finish(scene.camera);
                    assertVector(Angles.direction(30.0F, pitch), new Vector3f(0.0F, 0.0F, -1.0F).rotate(scene.rotation));
                    assertVector(Angles.direction(30.0F, pitch - 90.0F), new Vector3f(0.0F, 1.0F, 0.0F).rotate(scene.rotation));
                }
            }
        }
    }

    @Test
    public void aGravityRelativeCameraPreviewAndRestoreKeepThePersistentRoll() {
        GravityFrame gravity = new GravityFrame(AxisPermutation.of(Face.D, Face.E, Face.S));
        OpticTransform toward = OpticTransform.between(Frame.canonical(Face.N), Vec3d.ZERO, Frame.canonical(Face.U), Vec3d.ZERO);
        TravelMessage.TravelBegin arm = ClientTravelMotionTest.begin(toward.inverse());
        try (Scene scene = new Scene(new Vec3(0.5D, 1.62D, 0.7D))) {
            when(((GravityHolder) scene.player).wormholesGravity()).thenReturn(gravity);
            scene.rotation.set(GravityRenderTransform.camera(gravity, 30.0F, 10.0F));
            scene.view.update(scene.camera, scene.tracker, List.of(arm), null);
            assertTrue(scene.view.active());
            assertVector(toward.vector(gravity.toWorld(Angles.direction(30.0F, 10.0F))),
                new Vector3f(0.0F, 0.0F, -1.0F).rotate(scene.rotation));
            assertVector(toward.vector(gravity.toWorld(Angles.direction(30.0F, -80.0F))),
                new Vector3f(0.0F, 1.0F, 0.0F).rotate(scene.rotation));
            scene.view.finish(scene.camera);
            assertFalse(scene.view.active());
            assertVector(gravity.toWorld(Angles.direction(30.0F, 10.0F)), new Vector3f(0.0F, 0.0F, -1.0F).rotate(scene.rotation));
            assertVector(gravity.toWorld(Angles.direction(30.0F, -80.0F)), new Vector3f(0.0F, 1.0F, 0.0F).rotate(scene.rotation));
            assertEquals(30.0F, scene.camera.yRot(), 0.0F);
            assertEquals(10.0F, scene.camera.xRot(), 0.0F);
        }
    }

    @Test
    public void translationOnlyPreviewsKeepScalarYawAndTheRolledBasisAcrossRepeatedPoleFrames() {
        TravelMessage.TravelBegin arm = ClientTravelMotionTest.begin(OpticTransform.of(AxisPermutation.IDENTITY, 0, -20, 0));
        GravityFrame sideways = new GravityFrame(AxisPermutation.of(Face.D, Face.E, Face.S));
        for (GravityFrame gravity : List.of(GravityFrame.WORLD, sideways)) {
            for (boolean detached : new boolean[] {false, true}) {
                for (boolean front : new boolean[] {false, true}) {
                    for (float pitch : new float[] {-90.0F, -89.99998F, 89.99998F, 90.0F}) {
                        try (Scene scene = new Scene(new Vec3(0.5D, 1.62D, 0.7D))) {
                            when(scene.camera.isDetached()).thenReturn(detached);
                            float cameraYaw = front ? 217.0F : 37.0F;
                            float cameraPitch = front ? -pitch : pitch;
                            scene.orient(gravity, cameraYaw, cameraPitch, 53.0F);
                            Quaternionf original = new Quaternionf(scene.rotation);
                            for (int frame = 0; frame < 8; frame++) {
                                scene.view.update(scene.camera, scene.tracker, List.of(arm), null);
                                assertTrue(scene.view.active());
                                assertEquals(cameraYaw, scene.camera.yRot(), 0.0F);
                                assertEquals(cameraPitch, scene.camera.xRot(), 0.0F);
                                assertBasis(original, scene.rotation);
                                scene.view.finish(scene.camera);
                                assertFalse(scene.view.active());
                                assertEquals(cameraYaw, scene.camera.yRot(), 0.0F);
                                assertEquals(cameraPitch, scene.camera.xRot(), 0.0F);
                                assertBasis(original, scene.rotation);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void aProperQuarterTurnKeepsPoleHeadingAndRollForWorldAndGravityRelativeCameras() {
        AxisPermutation quarter = AxisPermutation.of(Face.S, Face.U, Face.W);
        OpticTransform toward = OpticTransform.of(quarter, 0, 0, 0);
        TravelMessage.TravelBegin arm = ClientTravelMotionTest.begin(toward.inverse());
        for (GravityFrame gravity : List.of(GravityFrame.WORLD, new GravityFrame(quarter.inverse()))) {
            for (float pitch : new float[] {-90.0F, -89.99998F, 10.0F, 89.99998F, 90.0F}) {
                try (Scene scene = new Scene(new Vec3(0.5D, 1.62D, 0.7D))) {
                    when(scene.camera.isDetached()).thenReturn(true);
                    scene.orient(gravity, 37.0F, pitch, 53.0F);
                    Quaternionf original = new Quaternionf(scene.rotation);
                    scene.view.update(scene.camera, scene.tracker, List.of(arm), null);
                    assertTrue(scene.view.active());
                    float expectedYaw = gravity.equals(GravityFrame.WORLD) ? 127.0F : 37.0F;
                    assertEquals(0.0F, Angles.unwrap(scene.camera.yRot() - expectedYaw, 0.0F), 1.0E-5F);
                    assertEquals(pitch, scene.camera.xRot(), 1.0E-5F);
                    assertMappedBasis(toward, original, scene.rotation);
                    scene.view.finish(scene.camera);
                    assertEquals(37.0F, scene.camera.yRot(), 0.0F);
                    assertEquals(pitch, scene.camera.xRot(), 0.0F);
                    assertBasis(original, scene.rotation);
                }
            }
        }
    }

    @Test
    public void aWallToFloorPreviewResolvesExactPolesWithoutSnappingRealNearPoleHeading() {
        OpticTransform toward = OpticTransform.of(AxisPermutation.of(Face.E, Face.S, Face.D), 0, 0, 0);
        TravelMessage.TravelBegin arm = ClientTravelMotionTest.begin(toward.inverse());
        for (float pitch : new float[] {-0.00002F, 0.0F, 0.00002F}) {
            try (Scene scene = new Scene(new Vec3(0.5D, 1.62D, 0.7D))) {
                scene.orient(GravityFrame.WORLD, 0.0F, pitch, 53.0F);
                Quaternionf original = new Quaternionf(scene.rotation);
                scene.view.update(scene.camera, scene.tracker, List.of(arm), null);
                assertTrue(scene.view.active());
                float expectedYaw = pitch > 0.0F ? 180.0F : 0.0F;
                assertEquals(0.0F, Angles.unwrap(scene.camera.yRot() - expectedYaw, 0.0F), 1.0E-5F);
                assertEquals(90.0F - Math.abs(pitch), scene.camera.xRot(), 1.0E-5F);
                assertMappedBasis(toward, original, scene.rotation);
                scene.view.finish(scene.camera);
                assertEquals(0.0F, scene.camera.yRot(), 0.0F);
                assertEquals(pitch, scene.camera.xRot(), 0.0F);
                assertBasis(original, scene.rotation);
            }
        }
    }

    @Test
    public void anArrivalReturnSurfaceKeepsBobbingStoppedBeforeTheNewWorldIsArmed() {
        Vec3 eye = new Vec3(0.5D, 71.62D, -39.499911199206885D);
        try (Scene scene = new Scene(eye)) {
            PortalView returning = scene.returning(Similarity.of(OpticTransform.of(AxisPermutation.IDENTITY, 0, 70, -40), 1.0D));
            TravelMessage.TravelBegin stale = mock(TravelMessage.TravelBegin.class);
            when(stale.sourceWorld()).thenReturn(Level.NETHER.identifier().toString());
            when(scene.player.getEyePosition(1.0F)).thenReturn(eye);
            when(scene.camera.yRot()).thenReturn(75.0F);
            scene.view.update(scene.camera, scene.tracker, List.of(stale), returning);
            assertEquals(0.0D, scene.view.bobFactor(), 0.0D);
            assertFalse(scene.view.active());
            scene.view.update(scene.camera, scene.tracker, List.of(stale), returning);
            assertEquals(0.0D, scene.view.bobFactor(), 0.0D);
            verify(scene.pose, never()).wormholes$position(any());
        }
    }

    @Test
    public void bobbingRecoversAfterLeavingThePlacedReturnOpening() {
        Vec3 eye = new Vec3(0.5D, 71.62D, -39.499911199206885D);
        try (Scene scene = new Scene(eye)) {
            PortalView returning = scene.returning(Similarity.of(OpticTransform.of(AxisPermutation.IDENTITY, 0, 70, -40), 1.0D));
            when(scene.player.getEyePosition(1.0F)).thenReturn(eye);
            scene.view.update(scene.camera, scene.tracker, List.of(), returning);
            Vec3 far = new Vec3(0.5D, 71.62D, -36.5D);
            when(scene.player.getEyePosition(1.0F)).thenReturn(far);
            when(scene.camera.position()).thenReturn(far);
            scene.view.update(scene.camera, scene.tracker, List.of(), returning);
            assertEquals(0.1D, scene.view.bobFactor(), 1.0E-9D);
            assertFalse(scene.view.active());
        }
    }

    @Test
    public void aReturnSurfaceFromAnotherWorldDoesNotSuppressBobbing() {
        try (Scene scene = new Scene(EYE)) {
            PortalView returning = scene.returning(Similarity.IDENTITY);
            when(returning.source()).thenReturn(mock(ClientLevel.class));
            scene.view.update(scene.camera, scene.tracker, List.of(), returning);
            assertEquals(1.0D, scene.view.bobFactor(), 0.0D);
            assertFalse(scene.view.active());
        }
    }

    @Test
    public void aScaledReturnUsesPlaneDistanceForIntermediateBobbingRecovery() {
        Vec3 eye = new Vec3(2.0D, 71.62D, -37.9999D);
        try (Scene scene = new Scene(eye)) {
            PortalView returning = scene.returning(Similarity.of(OpticTransform.of(AxisPermutation.IDENTITY, 0, 70, -40), 4.0D));
            when(scene.player.getEyePosition(1.0F)).thenReturn(eye);
            scene.view.update(scene.camera, scene.tracker, List.of(), returning);
            assertEquals(0.0D, scene.view.bobFactor(), 0.0D);
            Vec3 intermediate = new Vec3(2.0D, 71.62D, -36.5D);
            when(scene.player.getEyePosition(1.0F)).thenReturn(intermediate);
            when(scene.camera.position()).thenReturn(intermediate);
            scene.view.update(scene.camera, scene.tracker, List.of(), returning);
            assertEquals(0.05D, scene.view.bobFactor(), 1.0E-9D);
            Vec3 far = new Vec3(2.0D, 71.62D, -35.6D);
            when(scene.player.getEyePosition(1.0F)).thenReturn(far);
            when(scene.camera.position()).thenReturn(far);
            scene.view.update(scene.camera, scene.tracker, List.of(), returning);
            assertEquals(0.145D, scene.view.bobFactor(), 1.0E-9D);
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
        private final LocalPlayer player = mock(LocalPlayer.class, withSettings().extraInterfaces(GravityHolder.class));
        private final ClientLevel level = mock(ClientLevel.class);
        private final Camera camera = mock(Camera.class, withSettings().extraInterfaces(CameraPoseAccess.class, GravityCameraAccess.class));
        private final CameraPoseAccess pose = (CameraPoseAccess) camera;
        private final DeltaTracker tracker = mock(DeltaTracker.class);
        private final Quaternionf rotation = GravityRenderTransform.camera(GravityFrame.WORLD, 30.0F, 10.0F);
        private final Vector3f up = new Vector3f();
        private final Vector3f left = new Vector3f();
        private final TravelMessage.TravelBegin begin = ClientTravelMotionTest.begin(OpticTransform.of(AxisPermutation.of(Face.S, Face.U, Face.W), 0, 0, 0));
        private final ClientCrossingView view = new ClientCrossingView(new ResidentLevels(ignored -> { }, 512L << 20));
        private final MockedStatic<Minecraft> access;
        private float yaw = 30.0F;
        private float pitch = 10.0F;
        private Vec3 position;

        private Scene(Vec3 cameraPosition) {
            minecraft.player = player;
            minecraft.level = level;
            when(((GravityHolder) player).wormholesGravity()).thenReturn(GravityFrame.WORLD);
            position = cameraPosition;
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(player.getEyePosition(1.0F)).thenReturn(EYE);
            when(camera.isInitialized()).thenReturn(true);
            when(camera.entity()).thenReturn(player);
            when(camera.getCameraEntityPartialTicks(tracker)).thenReturn(1.0F);
            when(camera.position()).thenAnswer(invocation -> position);
            when(camera.yRot()).thenAnswer(invocation -> yaw);
            when(camera.xRot()).thenAnswer(invocation -> pitch);
            when(camera.rotation()).thenReturn(rotation);
            when(pose.wormholes$up()).thenReturn(up);
            when(pose.wormholes$left()).thenReturn(left);
            doAnswer(invocation -> {
                yaw = invocation.getArgument(0);
                pitch = invocation.getArgument(1);
                rotation.rotationYXZ((float) Math.toRadians(180.0F - yaw), (float) Math.toRadians(-pitch), 0.0F);
                up.set(0.0F, 1.0F, 0.0F).rotate(rotation);
                left.set(-1.0F, 0.0F, 0.0F).rotate(rotation);
                return null;
            }).when(pose).wormholes$rotation(anyFloat(), anyFloat());
            doAnswer(invocation -> {
                Quaternionf orientation = invocation.getArgument(0);
                rotation.set(orientation);
                up.set(0.0F, 1.0F, 0.0F).rotate(rotation);
                left.set(-1.0F, 0.0F, 0.0F).rotate(rotation);
                return null;
            }).when((GravityCameraAccess) camera).wormholes$orientation(any(Quaternionf.class));
            doAnswer(invocation -> {
                position = invocation.getArgument(0);
                return null;
            }).when(pose).wormholes$position(any(Vec3.class));
            when(player.getAttributeValue(Attributes.CAMERA_DISTANCE)).thenReturn(4.0D);
            when(player.getScale()).thenReturn(1.0F);
            BlockHitResult hit = mock(BlockHitResult.class);
            when(hit.getType()).thenReturn(HitResult.Type.MISS);
            when(level.clip(any())).thenReturn(hit);
            when(camera.getViewRotationMatrix(any())).thenReturn(new Matrix4f());
            when(pose.wormholes$cullingProjection()).thenReturn(new Matrix4f());
            access = mockStatic(Minecraft.class);
            access.when(Minecraft::getInstance).thenReturn(minecraft);
        }

        private PortalView returning(Similarity placement) {
            PortalView returning = mock(PortalView.class);
            when(returning.source()).thenReturn(level);
            when(returning.surface()).thenReturn(PortalSurface.of(ClientTravelTestFixtures.geometry()).through(placement));
            return returning;
        }

        private void orient(GravityFrame gravity, float cameraYaw, float cameraPitch, float roll) {
            when(((GravityHolder) player).wormholesGravity()).thenReturn(gravity);
            yaw = cameraYaw;
            pitch = cameraPitch;
            rotation.set(GravityRenderTransform.camera(gravity, yaw, pitch)).rotateZ((float) Math.toRadians(roll));
            up.set(0.0F, 1.0F, 0.0F).rotate(rotation);
            left.set(-1.0F, 0.0F, 0.0F).rotate(rotation);
        }

        @Override
        public void close() {
            access.close();
        }
    }

    private static void assertVector(Vec3d expected, Vector3f actual) {
        assertEquals(expected.x(), actual.x, 1.0E-5D);
        assertEquals(expected.y(), actual.y, 1.0E-5D);
        assertEquals(expected.z(), actual.z, 1.0E-5D);
    }

    private static void assertBasis(Quaternionf expected, Quaternionf actual) {
        assertMappedBasis(OpticTransform.IDENTITY, expected, actual);
    }

    private static void assertMappedBasis(OpticTransform toward, Quaternionf original, Quaternionf actual) {
        for (Vector3f axis : List.of(new Vector3f(0, 0, -1), new Vector3f(0, 1, 0), new Vector3f(1, 0, 0))) {
            Vector3f source = new Vector3f(axis).rotate(original);
            assertVector(toward.vector(new Vec3d(source.x, source.y, source.z)), new Vector3f(axis).rotate(actual));
        }
    }
}
