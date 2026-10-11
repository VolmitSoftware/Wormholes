package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.ClientPalette;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientViewSession;
import art.arcane.wormholes.modded.client.ResidentLevels;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.WormholesClientConfig;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class PortalViewsTest extends MinecraftTestBase {
    private static final Vec3d EYE = new Vec3d(1.0D, 65.0D, 4.0D);

    @Test
    public void secondCoplanarPortalOwnsReturnRegardlessOfArmOrder() {
        for (boolean reversed : new boolean[]{false, true}) {
            try (TravelScene travel = new TravelScene()) {
                TravelMessage.TravelBegin second = residentArm(UUID.randomUUID(), geometry(4, false));
                when(second.sourceToDestination()).thenReturn(travel.toward);
                List<TravelMessage.TravelBegin> arms = reversed ? List.of(second, travel.forward) : List.of(travel.forward, second);
                travel.scene.views.frame(travel.scene.home, new Vec3d(5.0D, 65.0D, 4.0D), arms, false, true, List.of(), null);
                PortalView entered = travel.scene.views.current().get(reversed ? 0 : 1);
                PortalView expected = PortalViews.futureTravelViews(entered).getLast();

                travel.scene.views.crossed(second, travel.scene.home, travel.destination, true);
                assertCamera(expected, travel.scene.views.follow(travel.destination));
                travel.scene.views.frame(travel.destination, travel.toward.point(new Vec3d(5.0D, 65.0D, 4.0D)),
                    List.of(), false, true, List.of(), null);
                assertEquals(1, travel.scene.views.current().size());
                assertCamera(expected, travel.scene.views.current().getFirst());
            }
        }
    }

    @Test
    public void crossingUsesArmSnapshotWhenNoSourceViewWasRendered() {
        try (TravelScene travel = new TravelScene()) {
            PortalView expected = PortalViews.futureTravelViews(travel.scene.views.current().getFirst()).getLast();
            travel.scene.views.frame(travel.scene.home, EYE, List.of(), false, true, List.of(), null);
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            assertCamera(expected, travel.scene.views.follow(travel.destination));
        }
    }

    @Test
    public void returnSurvivesArmRemovalAndResidentHandleReuseAfterCrossing() {
        try (TravelScene travel = new TravelScene()) {
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            PortalView expected = travel.scene.views.follow(travel.destination);
            when(travel.scene.residents.level(1)).thenReturn(travel.scene.home);
            travel.scene.views.frame(travel.destination, travel.toward.point(EYE), List.of(), false, true, List.of(), null);
            assertSame(expected, travel.scene.views.current().getFirst());
        }
    }

    @Test
    public void crossingIntoANonmatchingResidentNeverBorrowsAnotherArm() {
        try (TravelScene travel = new TravelScene()) {
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            ClientLevel unrelated = mock(ClientLevel.class);
            when(unrelated.dimension()).thenReturn(Level.END);
            travel.scene.views.crossed(travel.forward, travel.scene.home, unrelated, true);
            assertNull(travel.scene.views.follow(unrelated));
            travel.scene.views.frame(unrelated, EYE, List.of(), false, true, List.of(), null);
            assertTrue(travel.scene.views.current().isEmpty());
        }
    }

    @Test
    public void nonresidentArmCannotReusePreviousReturnOwnership() {
        try (TravelScene travel = new TravelScene()) {
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            when(travel.forward.resident()).thenReturn(false);
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            assertNull(travel.scene.views.follow(travel.destination));
        }
    }

    @Test
    public void wrongSourceWorldAndUnavailableDepartedRendererCannotCreateReturn() {
        try (TravelScene travel = new TravelScene()) {
            when(travel.forward.sourceWorld()).thenReturn("minecraft:the_nether");
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            assertNull(travel.scene.views.follow(travel.destination));
            when(travel.forward.sourceWorld()).thenReturn("minecraft:overworld");
            travel.worlds.when(() -> ClientWorldLoader.residentRenderer(travel.scene.home)).thenReturn(null);
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            assertNull(travel.scene.views.follow(travel.destination));
        }
    }

    @Test
    public void unknownArmAndSameLevelMovementInvalidatePreviousReturn() {
        try (TravelScene travel = new TravelScene()) {
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            assertTrue(travel.scene.views.follow(travel.destination) != null);
            travel.scene.views.crossed(null, travel.destination, travel.scene.home, true);
            assertNull(travel.scene.views.follow(travel.scene.home));
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            travel.scene.views.crossed(travel.reverse(UUID.randomUUID(), reverseGeometry(false)), travel.destination, travel.destination, true);
            assertNull(travel.scene.views.follow(travel.destination));
        }
    }

    @Test
    public void sameDimensionResidentSwapStillUsesExactLevelIdentity() {
        try (TravelScene travel = new TravelScene()) {
            when(travel.destination.dimension()).thenReturn(Level.OVERWORLD);
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            PortalView returning = travel.scene.views.follow(travel.destination);
            assertSame(travel.destination, returning.source());
            assertSame(travel.scene.home, returning.destination());
        }
    }

    @Test
    public void unannouncedHomeChangeDoesNotInferReturnFromPreviouslyRenderedArms() {
        try (TravelScene travel = new TravelScene()) {
            assertNull(travel.scene.views.follow(travel.destination));
            travel.scene.views.frame(travel.destination, travel.toward.point(EYE), List.of(), false, true, List.of(), null);
            assertTrue(travel.scene.views.current().isEmpty());
        }
    }

    @Test
    public void rollbackClearsReturnAndPendingCameraContinuationsWithoutReinferring() {
        try (TravelScene travel = new TravelScene()) {
            travel.arrive(List.of(travel.reverse(UUID.randomUUID(), reverseGeometry(false))));
            travel.scene.views.resetReturn(travel.scene.home);
            assertTrue(travel.scene.views.takeCameraContinuations().isEmpty());
            assertNull(travel.scene.views.follow(travel.scene.home));
            travel.scene.views.frame(travel.destination, travel.toward.point(EYE), List.of(), false, true, List.of(), null);
            assertTrue(travel.scene.views.current().isEmpty());
        }
    }

    @Test
    public void latestCrossingOwnsReturnWithoutAnIntermediateRenderFrame() {
        try (TravelScene travel = new TravelScene()) {
            TravelMessage.TravelBegin reverse = travel.reverse(UUID.randomUUID(), reverseGeometry(false));
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            PortalView forwardReturn = travel.scene.views.follow(travel.destination);
            travel.scene.views.crossed(reverse, travel.destination, travel.scene.home, true);
            PortalView reverseReturn = travel.scene.views.follow(travel.scene.home);
            assertNotEquals(forwardReturn.key(), reverseReturn.key());
            assertSame(travel.scene.home, reverseReturn.source());
            assertSame(travel.destination, reverseReturn.destination());
            assertEquals(reverse.sourceGeometry(), reverseReturn.surface().geometry());
            travel.scene.views.frame(travel.scene.home, EYE, List.of(), false, true, List.of(), null);
            assertSame(reverseReturn, travel.scene.views.current().getFirst());
            assertTrue(travel.scene.views.takeCameraContinuations().isEmpty());
        }
    }

    @Test
    public void newCrossingExpiresThePreviousFramesCameraContinuations() {
        try (TravelScene travel = new TravelScene()) {
            TravelMessage.TravelBegin reverse = travel.reverse(UUID.randomUUID(), reverseGeometry(false));
            travel.arrive(List.of(reverse));
            travel.scene.views.crossed(reverse, travel.destination, travel.scene.home, true);
            assertTrue(travel.scene.views.takeCameraContinuations().isEmpty());
            assertTrue(travel.scene.views.follow(travel.scene.home) != null);
        }
    }

    @Test
    public void crossingKeepsCurrentRecursionChoiceWhenSourceViewIsMissing() {
        try (TravelScene travel = new TravelScene()) {
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, false);
            assertEquals(1, travel.scene.views.follow(travel.destination).recursion());
        }
    }

    @Test
    public void matchingReverseArmContinuesAllFourCameraRolesAndRestoresThemOnReturn() {
        try (TravelScene travel = new TravelScene()) {
            PortalView original = travel.scene.views.current().getFirst();
            TravelMessage.TravelBegin reverse = travel.reverse(UUID.randomUUID(), reverseGeometry(false));
            travel.arrive(List.of(reverse));
            assertEquals(1, travel.scene.views.current().size());
            PortalView armed = travel.scene.views.current().getFirst();
            assertEquals(PortalView.Kind.ARM, armed.kind());
            List<PortalViews.CameraContinuation> outbound = travel.scene.views.takeCameraContinuations();
            assertEquals(4, outbound.size());
            assertSame(original, outbound.getFirst().previous());
            assertSame(armed, outbound.getLast().next());
            for (PortalViews.CameraContinuation continuation : outbound) {
                assertTrue(PortalViews.sameCamera(continuation.previous(), continuation.next()));
                assertNotEquals(continuation.previous().key(), continuation.next().key());
            }
            assertTrue(travel.scene.views.takeCameraContinuations().isEmpty());
            travel.scene.views.crossed(reverse, travel.destination, travel.scene.home, true);
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward, reverse), false, true, List.of(), null);
            assertEquals(original.key(), travel.scene.views.current().getFirst().key());
            List<PortalViews.CameraContinuation> inbound = travel.scene.views.takeCameraContinuations();
            assertEquals(4, inbound.size());
            for (int index = 0; index < 4; index++) {
                PortalViews.CameraContinuation first = outbound.get(index);
                PortalViews.CameraContinuation second = inbound.get(3 - index);
                assertEquals(first.next().key(), second.previous().key());
                assertEquals(first.previous().key(), second.next().key());
                assertTrue(PortalViews.sameCamera(second.previous(), second.next()));
            }
        }
    }

    @Test
    public void oppositeServedSideKeepsIndependentCameraBeforeMatchingSideArrives() {
        try (TravelScene travel = new TravelScene()) {
            UUID portal = UUID.randomUUID();
            travel.arrive(List.of(travel.reverse(portal, reverseGeometry(true))));
            assertEquals(2, travel.scene.views.current().size());
            PortalView independent = travel.scene.views.current().getFirst();
            PortalView returning = travel.scene.views.current().getLast();
            assertFalse(PortalViews.sameCamera(returning, independent));
            assertTrue(travel.scene.views.takeCameraContinuations().isEmpty());
            travel.arrive(List.of(travel.reverse(portal, reverseGeometry(false))));
            List<PortalViews.CameraContinuation> continuations = travel.scene.views.takeCameraContinuations();
            assertEquals(4, continuations.size());
            for (PortalViews.CameraContinuation continuation : continuations) {
                assertNotEquals(independent.key(), continuation.previous().key());
                assertNotEquals(independent.key(), continuation.next().key());
            }
        }
    }

    @Test
    public void coincidentIndependentReversePortalsCannotClaimTheSameReturnHistory() {
        try (TravelScene travel = new TravelScene()) {
            TravelMessage.TravelBegin first = travel.reverse(UUID.randomUUID(), reverseGeometry(false));
            TravelMessage.TravelBegin second = travel.reverse(UUID.randomUUID(), reverseGeometry(false));
            travel.arrive(List.of(first, second));
            assertEquals(3, travel.scene.views.current().size());
            assertNotEquals(travel.scene.views.current().getFirst().key(), travel.scene.views.current().get(1).key());
            assertEquals(PortalView.Kind.RETURN, travel.scene.views.current().getLast().kind());
            assertTrue(travel.scene.views.takeCameraContinuations().isEmpty());
        }
    }

    @Test
    public void unconsumedCameraContinuationsExpireAtTheNextFrame() {
        try (TravelScene travel = new TravelScene()) {
            TravelMessage.TravelBegin reverse = travel.reverse(UUID.randomUUID(), reverseGeometry(false));
            travel.arrive(List.of(reverse));
            travel.arrive(List.of(reverse));
            assertTrue(travel.scene.views.takeCameraContinuations().isEmpty());
        }
    }

    @Test
    public void coincidentSurfaceWithDifferentDestinationTransformOrShapeCannotSuppressReturn() {
        for (int mismatch = 0; mismatch < 4; mismatch++) {
            try (TravelScene travel = new TravelScene()) {
                ApertureDescriptor geometry = reverseGeometry(false);
                if (mismatch == 2) {
                    geometry = geometry.withShape(ShapeDescriptor.parse("circle"));
                }
                if (mismatch == 3) {
                    geometry = reverseGeometry(false, 31L);
                }
                TravelMessage.TravelBegin reverse = travel.reverse(UUID.randomUUID(), geometry);
                if (mismatch == 0) {
                    when(travel.scene.residents.level(2)).thenReturn(mock(ClientLevel.class));
                }
                if (mismatch == 1) {
                    when(reverse.sourceToDestination()).thenReturn(translation(-190.0D));
                }
                travel.arrive(List.of(reverse));
                assertEquals(2, travel.scene.views.current().size());
                assertEquals(PortalView.Kind.RETURN, travel.scene.views.current().getLast().kind());
                assertTrue(travel.scene.views.takeCameraContinuations().isEmpty());
            }
        }
    }

    private static ApertureDescriptor reverseGeometry(boolean front) {
        return reverseGeometry(front, 63L);
    }

    private static Similarity translation(double z) {
        Frame frame = Frame.canonical(Face.S);
        return Similarity.of(OpticTransform.between(frame, new Vec3d(0, 0, 0), frame, new Vec3d(0, 0, z)), 1.0D);
    }

    private static ApertureDescriptor reverseGeometry(boolean front, long mask) {
        return new ApertureDescriptor(0, 64, 200, Face.S.ordinal(), front, 0, false, 2, 3, new long[]{mask}, ShapeDescriptor.FULL,
            0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of());
    }

    @Test
    public void futureTravelViewsMatchLiveCameraRootsBeforeAndAfterPrediction() {
        try (Scene scene = new Scene(); MockedStatic<ClientWorldLoader> worlds = mockStatic(ClientWorldLoader.class)) {
            ClientLevel destination = mock(ClientLevel.class);
            when(destination.dimension()).thenReturn(Level.END);
            when(scene.residents.level(1)).thenReturn(destination);
            LevelRenderer renderer = mock(LevelRenderer.class);
            worlds.when(() -> ClientWorldLoader.residentRenderer(scene.home)).thenReturn(renderer);
            TravelMessage.TravelBegin arm = residentArm(UUID.randomUUID(), geometry(0, false));
            PortalView original = scene.arm(arm);
            List<PortalView> future = PortalViews.futureTravelViews(original);
            assertEquals(3, future.size());
            scene.views.frame(scene.home, EYE, List.of(arm), false, true, List.of(),
                new PortalViews.Crossing(arm.token(), destination, original.surface(), arm.sourceToDestination()));
            assertCamera(future.get(1), crossing(scene.views));
            Vec3d destinationEye = original.toDestination().point(EYE);
            scene.views.crossed(arm, scene.home, destination, true);
            scene.views.frame(destination, destinationEye, List.of(arm), false, true, List.of(), null);
            PortalView returning = scene.views.current().getFirst();
            assertCamera(future.get(2), returning);
            scene.views.frame(destination, destinationEye, List.of(arm), false, true, List.of(),
                new PortalViews.Crossing(returning.key(), scene.home, returning.surface(), returning.toDestination()));
            assertCamera(future.getFirst(), crossing(scene.views));
        }
    }

    @Test
    public void futureCamerasKeepCoincidentPortalsSeparateAndIgnoreReplacementTokens() {
        try (Scene scene = new Scene()) {
            ClientLevel destination = mock(ClientLevel.class);
            when(destination.dimension()).thenReturn(Level.END);
            when(scene.residents.level(1)).thenReturn(destination);
            UUID portal = UUID.randomUUID();
            TravelMessage.TravelBegin arm = residentArm(portal, geometry(0, false));
            List<PortalView> first = PortalViews.futureTravelViews(scene.arm(arm));
            List<PortalView> refreshed = PortalViews.futureTravelViews(scene.arm(residentArm(portal, presentation(arm.sourceGeometry()))));
            List<PortalView> coincident = PortalViews.futureTravelViews(scene.arm(residentArm(UUID.randomUUID(), arm.sourceGeometry())));
            for (int index = 0; index < first.size(); index++) {
                assertEquals(first.get(index).key(), refreshed.get(index).key());
                assertNotEquals(first.get(index).key(), coincident.get(index).key());
            }
        }
    }

    private static PortalView crossing(PortalViews views) {
        return views.current().stream().filter(view -> view.kind() == PortalView.Kind.CROSSING).findFirst().orElseThrow();
    }

    private static void assertCamera(PortalView expected, PortalView actual) {
        assertEquals(expected.kind(), actual.kind());
        assertEquals(expected.key(), actual.key());
        assertSame(expected.source(), actual.source());
        assertSame(expected.destination(), actual.destination());
        assertEquals(expected.surface().geometry(), actual.surface().geometry());
        assertEquals(expected.surface().placement(), actual.surface().placement());
        assertEquals(expected.surface().reversed(), actual.surface().reversed());
        assertEquals(expected.toDestination(), actual.toDestination());
    }

    private static TravelMessage.TravelBegin residentArm(UUID portal, ApertureDescriptor geometry) {
        TravelMessage.TravelBegin arm = arm(portal, geometry);
        when(arm.resident()).thenReturn(true);
        when(arm.levelHandle()).thenReturn(1);
        when(arm.sourceToDestination()).thenReturn(Similarity.of(OpticTransform.IDENTITY, 2.0D));
        return arm;
    }

    @Test
    public void mirrorRootsFollowTheirSourceAcrossPhysicalHomeChanges() {
        try (Scene scene = new Scene()) {
            ClientLevel destination = mock(ClientLevel.class);
            when(destination.dimension()).thenReturn(Level.END);
            ClientPortal sourceMirror = new ClientPortal(1, geometry(0, true), 1, 0.0D);
            ClientPortal destinationMirror = new ClientPortal(2, geometry(16, true), 1, 0.0D);
            ClientPortal nestedMirror = new ClientPortal(3, geometry(32, true).withParent(2), 1, 0.0D);
            scene.source(sourceMirror, scene.home);
            scene.source(destinationMirror, destination);
            scene.source(nestedMirror, destination);
            List<ClientPortal> portals = List.of(sourceMirror, destinationMirror, nestedMirror);

            scene.views.frame(scene.home, EYE, List.of(), true, true, portals, null);
            assertEquals(1, scene.views.current().size());
            PortalView original = scene.views.current().getFirst();
            assertSame(sourceMirror.geometry(), original.surface().geometry());
            assertSame(scene.home, original.source());

            scene.views.frame(destination, EYE, List.of(), true, true, portals, null);
            assertEquals(1, scene.views.current().size());
            PortalView arrived = scene.views.current().getFirst();
            assertEquals(PortalView.Kind.MIRROR, arrived.kind());
            assertSame(destinationMirror.geometry(), arrived.surface().geometry());
            assertSame(destination, arrived.source());
            assertSame(destination, arrived.destination());
            assertNotEquals(original.key(), arrived.key());
            assertTrue(scene.views.takeCameraContinuations().isEmpty());

            scene.views.frame(scene.home, EYE, List.of(), true, true, portals, null);
            assertEquals(1, scene.views.current().size());
            PortalView returned = scene.views.current().getFirst();
            assertSame(sourceMirror.geometry(), returned.surface().geometry());
            assertSame(scene.home, returned.source());
            assertEquals(original.key(), returned.key());
            assertNotSame(original, returned);
        }
    }

    @Test
    public void mirrorRootsWithoutSourceIdentityAreExcluded() {
        try (Scene scene = new Scene()) {
            ClientPortal mirror = new ClientPortal(1, geometry(0, true), 1, 0.0D);
            scene.session.portals().put(mirror.portalKey(), mirror);
            scene.views.frame(scene.home, EYE, List.of(), true, true, List.of(mirror), null);
            assertTrue(scene.views.current().isEmpty());
        }
    }

    @Test
    public void mirrorsKeepPhysicalIdentityAcrossRevisionsAndNewStreamSlots() {
        try (Scene scene = new Scene()) {
            ClientPortal portal = new ClientPortal(1, geometry(0, true), 1, 0.0D);
            PortalView original = scene.mirror(portal, true);
            portal.geometry(geometry(0, true), 2);
            assertSame(original, scene.mirror(portal, true));
            scene.views.frame(scene.home, EYE, List.of(), false, true, List.of(), null);
            assertTrue(scene.views.current().isEmpty());
            PortalView reopened = scene.mirror(new ClientPortal(9, geometry(0, true), 7, 0.0D), true);
            assertNotSame(original, reopened);
            assertEquals(original.key(), reopened.key());
            assertEquals(original.key().hashCode(), reopened.key().hashCode());
        }
    }

    @Test
    public void mirrorPresentationAndRecursionChangesRefreshTheViewWithoutChangingItsCameraKey() {
        try (Scene scene = new Scene()) {
            ClientPortal portal = new ClientPortal(1, geometry(0, true), 1, 0.0D);
            PortalView original = scene.mirror(portal, true);
            ApertureDescriptor next = presentation(portal.geometry());
            portal.geometry(next, 2);
            PortalView refreshed = scene.mirror(portal, true);
            assertNotSame(original, refreshed);
            assertEquals(original.key(), refreshed.key());
            assertSame(next, refreshed.surface().geometry());
            assertEquals(7, refreshed.recursion());
            PortalView shallow = scene.mirror(portal, false);
            assertNotSame(refreshed, shallow);
            assertEquals(refreshed.key(), shallow.key());
            assertEquals(1, shallow.recursion());
            assertSame(shallow, scene.mirror(portal, false));
        }
    }

    @Test
    public void differentPhysicalMirrorsAndChangedShapesKeepDistinctKeys() {
        try (Scene scene = new Scene()) {
            ClientPortal first = new ClientPortal(1, geometry(0, true), 1, 0.0D);
            ClientPortal second = new ClientPortal(2, geometry(16, true), 1, 0.0D);
            scene.source(first, scene.home);
            scene.source(second, scene.home);
            scene.views.frame(scene.home, EYE, List.of(), true, true, List.of(first, second), null);
            PortalView original = scene.views.current().getFirst();
            assertNotEquals(original.key(), scene.views.current().get(1).key());
            first.geometry(first.geometry().withShape(ShapeDescriptor.parse("circle")), 2);
            assertNotEquals(original.key(), scene.mirror(first, true).key());
        }
    }

    @Test
    public void mirrorMaskAndPlaneChangesInvalidateCameraIdentity() {
        try (Scene scene = new Scene()) {
            PortalView original = scene.mirror(new ClientPortal(1, geometry(0, true), 1, 0.0D), true);
            ApertureDescriptor masked = new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, true, 2, 3,
                new long[]{31L}, ShapeDescriptor.FULL, 0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of());
            assertNotEquals(original.key(), scene.mirror(new ClientPortal(1, masked, 2, 0.0D), true).key());
            ApertureDescriptor shifted = new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, true, 2, 3,
                new long[]{63L}, ShapeDescriptor.FULL, 0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.25D, 0, 0L, List.of());
            assertNotEquals(original.key(), scene.mirror(new ClientPortal(1, shifted, 3, 0.0D), true).key());
        }
    }

    @Test
    public void armsKeepPhysicalIdentityAcrossNewTokensAndPresentationChanges() {
        try (Scene scene = new Scene()) {
            UUID portal = UUID.randomUUID();
            TravelMessage.TravelBegin first = arm(portal, geometry(0, false));
            PortalView original = scene.arm(first);
            TravelMessage.TravelBegin second = arm(portal, presentation(first.sourceGeometry()));
            assertNotEquals(first.token(), second.token());
            PortalView refreshed = scene.arm(second);
            assertNotSame(original, refreshed);
            assertEquals(original.key(), refreshed.key());
            assertEquals(original.key().hashCode(), refreshed.key().hashCode());
            assertSame(second.sourceGeometry(), refreshed.surface().geometry());
            assertEquals(7, refreshed.recursion());
        }
    }

    @Test
    public void armPortalGeometryAndTransformChangesInvalidateCameraIdentity() {
        try (Scene scene = new Scene()) {
            UUID portal = UUID.randomUUID();
            PortalView original = scene.arm(arm(portal, geometry(0, false)));
            assertNotEquals(original.key(), scene.arm(arm(UUID.randomUUID(), geometry(0, false))).key());
            assertNotEquals(original.key(), scene.arm(arm(portal, geometry(16, false))).key());
            TravelMessage.TravelBegin changed = arm(portal, geometry(0, false));
            when(changed.sourceToDestination()).thenReturn(Similarity.of(OpticTransform.IDENTITY, 2.0D));
            assertNotEquals(original.key(), scene.arm(changed).key());
        }
    }

    @Test
    public void crossingWithTheSameTokenRefreshesItsTransformAndSurface() {
        try (Scene scene = new Scene()) {
            ClientLevel source = mock(ClientLevel.class);
            ApertureDescriptor geometry = geometry(0, false);
            scene.views.frame(scene.home, EYE, List.of(), false, true, List.of(),
                new PortalViews.Crossing("crossing", source, PortalSurface.of(geometry), Similarity.IDENTITY));
            PortalView original = scene.views.current().getFirst();
            Similarity toward = Similarity.of(OpticTransform.IDENTITY, 2.0D);
            scene.views.frame(scene.home, EYE, List.of(), false, true, List.of(),
                new PortalViews.Crossing("crossing", source, PortalSurface.of(geometry), toward));
            PortalView refreshed = scene.views.current().getFirst();
            assertNotSame(original, refreshed);
            assertEquals(original.key(), refreshed.key());
            assertEquals(toward, refreshed.surface().placement());
            assertEquals(toward.inverse(), refreshed.toDestination());
            ApertureDescriptor next = geometry.withShape(ShapeDescriptor.parse("circle"));
            scene.views.frame(scene.home, EYE, List.of(), false, true, List.of(),
                new PortalViews.Crossing("crossing", source, PortalSurface.of(next), toward));
            assertNotSame(refreshed, scene.views.current().getFirst());
            assertSame(next, scene.views.current().getFirst().surface().geometry());
        }
    }

    private static TravelMessage.TravelBegin arm(UUID portal, ApertureDescriptor geometry) {
        TravelMessage.TravelBegin arm = mock(TravelMessage.TravelBegin.class);
        when(arm.token()).thenReturn(UUID.randomUUID());
        when(arm.sourcePortal()).thenReturn(portal);
        when(arm.sourceWorld()).thenReturn("minecraft:overworld");
        when(arm.sourceGeometry()).thenReturn(geometry);
        when(arm.sourceToDestination()).thenReturn(Similarity.IDENTITY);
        return arm;
    }

    private static ApertureDescriptor geometry(int x, boolean mirror) {
        return new ApertureDescriptor(x, 64, 0, Face.S.ordinal(), true, 0, mirror, 2, 3, new long[]{63L}, ShapeDescriptor.FULL,
            0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of());
    }

    private static ApertureDescriptor presentation(ApertureDescriptor geometry) {
        return new ApertureDescriptor(geometry.originX(), geometry.originY(), geometry.originZ(), geometry.facing(), geometry.frontSide(),
            geometry.quarterTurns(), geometry.mirror(), geometry.apertureWidth(), geometry.apertureHeight(), geometry.apertureMask(),
            geometry.shape(), 0.25F, 0.5F, 0.75F, 32, 7, 2, 8, 1, 1, 7, 6, geometry.planeOffset(), 0, 99L,
            List.of(geometry.withDepth(8)));
    }

    private static final class Scene implements AutoCloseable {
        private final ClientLevel home = mock(ClientLevel.class);
        private final ResidentLevels residents = mock(ResidentLevels.class);
        private final PortalViews views = new PortalViews(residents);
        private final MockedStatic<PortalBackends> backends = mockStatic(PortalBackends.class);
        private final WormholesClient client = mock(WormholesClient.class);
        private final MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class);
        private final ClientViewSession session;

        private Scene() {
            when(home.dimension()).thenReturn(Level.OVERWORLD);
            backends.when(PortalBackends::pipeline).thenReturn(PipelineBackend.NONE);
            WormholesClientConfig config = new WormholesClientConfig();
            config.normalize();
            session = new ClientViewSession(config, new ClientPalette(BuiltInRegistries.BLOCK), 1, "test");
            session.accept(new ViewStreamMessage.Accept(1, ViewStreamCapability.CLIENT_MIRROR.mask(), 20,
                ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 7L, 8));
            clients.when(WormholesClient::instance).thenReturn(client);
            when(client.session()).thenReturn(session);
        }

        private void source(ClientPortal portal, ClientLevel source) {
            session.portals().put(portal.portalKey(), portal);
            session.bindSourceWorld(source.dimension().identifier().toString());
        }

        private PortalView mirror(ClientPortal portal, boolean recursion) {
            source(portal, home);
            views.frame(home, EYE, List.of(), true, recursion, List.of(portal), null);
            return views.current().getFirst();
        }

        private PortalView arm(TravelMessage.TravelBegin arm) {
            views.frame(home, EYE, List.of(arm), false, true, List.of(), null);
            return views.current().getFirst();
        }

        @Override
        public void close() {
            views.clear();
            backends.close();
            clients.close();
        }
    }

    private static final class TravelScene implements AutoCloseable {
        private final Scene scene = new Scene();
        private final MockedStatic<ClientWorldLoader> worlds = mockStatic(ClientWorldLoader.class);
        private final ClientLevel destination = mock(ClientLevel.class);
        private final TravelMessage.TravelBegin forward = residentArm(UUID.randomUUID(), geometry(0, false));
        private final Similarity toward = translation(200.0D);
        private boolean arrived;

        private TravelScene() {
            when(destination.dimension()).thenReturn(Level.END);
            when(scene.residents.level(1)).thenReturn(destination);
            when(scene.residents.level(2)).thenReturn(scene.home);
            LevelRenderer renderer = mock(LevelRenderer.class);
            worlds.when(() -> ClientWorldLoader.residentRenderer(scene.home)).thenReturn(renderer);
            worlds.when(() -> ClientWorldLoader.residentRenderer(destination)).thenReturn(renderer);
            when(forward.sourceToDestination()).thenReturn(toward);
            scene.arm(forward);
        }

        private TravelMessage.TravelBegin reverse(UUID portal, ApertureDescriptor geometry) {
            TravelMessage.TravelBegin reverse = residentArm(portal, geometry);
            when(reverse.sourceWorld()).thenReturn("minecraft:the_end");
            when(reverse.levelHandle()).thenReturn(2);
            when(reverse.sourceToDestination()).thenReturn(translation(-200.0D));
            return reverse;
        }

        private void arrive(List<TravelMessage.TravelBegin> arms) {
            if (!arrived) {
                scene.views.crossed(forward, scene.home, destination, true);
                arrived = true;
            }
            scene.views.frame(destination, toward.point(EYE), arms, false, true, List.of(), null);
        }

        @Override
        public void close() {
            scene.close();
            worlds.close();
        }
    }
}
