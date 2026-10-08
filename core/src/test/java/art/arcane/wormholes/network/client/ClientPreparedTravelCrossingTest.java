package art.arcane.wormholes.network.client;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.wormholes.render.client.session.ClientPreparedTravelServer;
import art.arcane.optics.math.Face;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.portal.ApertureKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPreparedTravelCrossingTest {
    @Test
    void acknowledgedCrossingIsOneShotAndCannotChooseItsDestination() {
        Fixture fixture = ready();
        TravelMessage.TravelCross crossing = crossing(fixture, new TravelMessage.TravelPose(0.5, 64, 0.4, 0, 0),
            new Vec3d(0.5, 65.62, 0.6), new Vec3d(0.5, 65.62, 0.4));
        assertTrue(fixture.server().requestCross(crossing, 3));
        assertFalse(fixture.server().requestCross(crossing, 3));
        assertEquals(crossing, fixture.server().takeCross().orElseThrow());
        assertTrue(fixture.server().takeCross().isEmpty());
        assertTrue(fixture.server().validCross(crossing, authority(fixture, fixture.begin().sourceGeometry(),
            new TravelMessage.TravelPose(0.5, 64, 0.55, 0, 0)), 4));
        TravelMessage.TravelCommit commit = fixture.server().commit(new ClientPreparedTravelServer.Commit(
            fixture.begin().sourcePortal(), fixture.begin().sourceWorld(), fixture.begin().world().dimension(),
            fixture.begin().arrival(), new Vec3d(0, 0, 0.2), 4)).orElseThrow();
        assertEquals(fixture.barrier(), commit.contentRevision());
        assertEquals(new Vec3d(0, 0, 0.2), commit.velocity());
        assertFalse(fixture.server().requestCross(crossing, 5));
    }

    @Test
    void ordinaryDestinationChangesDoNotRevokeAnAcknowledgedDrawableCrossing() {
        Fixture fixture = ready();
        TravelMessage.TravelCoordinate position = fixture.begin().chunks().getFirst();
        fixture.server().invalidate(position);
        assertTrue(fixture.server().column(position, 2, new byte[]{2}));
        List<TravelMessage> sent = new ArrayList<>();
        fixture.server().tick(3, TravelMessage.TRAVEL_FRAGMENT_BYTES, sent::add);
        assertTrue(fixture.server().ready(new TravelMessage.TravelReady(fixture.begin().token(), fixture.begin().generation(), fixture.barrier())));
        TravelMessage.TravelCross crossing = crossing(fixture, new TravelMessage.TravelPose(0.5, 64, 0.4, 0, 0),
            new Vec3d(0.5, 65.62, 0.6), new Vec3d(0.5, 65.62, 0.4));
        assertTrue(fixture.server().requestCross(crossing, 4));
        assertTrue(fixture.server().validCross(crossing, authority(fixture, fixture.begin().sourceGeometry(),
            new TravelMessage.TravelPose(0.5, 64, 0.55, 0, 0)), 4));
        assertEquals(fixture.barrier(), fixture.server().commit(new ClientPreparedTravelServer.Commit(
            fixture.begin().sourcePortal(), fixture.begin().sourceWorld(), fixture.begin().world().dimension(),
            fixture.begin().arrival(), new Vec3d(0, 0, 0), 4)).orElseThrow().contentRevision());
    }

    @Test
    void unacknowledgedAndForeignTokenRevisionOrGenerationCannotCross() {
        Fixture fixture = ready();
        for (TravelMessage.TravelCross value : List.of(
            new TravelMessage.TravelCross(UUID.randomUUID(), fixture.begin().generation(), fixture.barrier(), fixture.begin().arrival(), new Vec3d(0, 0, 0), new Vec3d(0, 0, 0)),
            new TravelMessage.TravelCross(fixture.begin().token(), fixture.begin().generation() + 1, fixture.barrier(), fixture.begin().arrival(), new Vec3d(0, 0, 0), new Vec3d(0, 0, 0)),
            new TravelMessage.TravelCross(fixture.begin().token(), fixture.begin().generation(), fixture.barrier() + 1, fixture.begin().arrival(), new Vec3d(0, 0, 0), new Vec3d(0, 0, 0)))) {
            assertFalse(fixture.server().requestCross(value, 3));
        }
        assertTrue(fixture.server().takeCross().isEmpty());
    }

    @Test
    void observedMovementEyeHeightAndActualApertureAreRequired() {
        Fixture fixture = ready();
        TravelMessage.TravelCross valid = crossing(fixture, new TravelMessage.TravelPose(0.5, 64, 0.4, 0, 0),
            new Vec3d(0.5, 65.62, 0.6), new Vec3d(0.5, 65.62, 0.4));
        assertTrue(fixture.server().requestCross(valid, 3));
        assertFalse(fixture.server().validCross(valid, authority(fixture, fixture.begin().sourceGeometry(),
            new TravelMessage.TravelPose(10, 64, 0.55, 0, 0)), 4));
        assertFalse(fixture.server().validCross(valid, new ClientPreparedTravelServer.Authority("other:world", fixture.begin().sourceGeometry(),
            new TravelMessage.TravelPose(0.5, 64, 0.55, 0, 0), new Vec3d(0, 0, 0), 1.62), 4));
        assertFalse(fixture.server().validCross(crossing(fixture, valid.sourcePose(), valid.previousEye(), new Vec3d(0.5, 66, 0.4)),
            authority(fixture, fixture.begin().sourceGeometry(), valid.sourcePose()), 4));
        assertFalse(fixture.server().validCross(crossing(fixture, new TravelMessage.TravelPose(2, 64, 0.4, 0, 0),
            new Vec3d(2, 65.62, 0.6), new Vec3d(2, 65.62, 0.4)),
            authority(fixture, fixture.begin().sourceGeometry(), new TravelMessage.TravelPose(2, 64, 0.55, 0, 0)), 4));
        assertFalse(fixture.server().validCross(valid, authority(fixture, fixture.begin().sourceGeometry(), valid.sourcePose()), 2_003));
    }

    @Test
    void unloadWorldInvalidationAndScopedCancellationRevokeCrossing() {
        Fixture fixture = ready();
        TravelMessage.TravelCross crossing = crossing(fixture, new TravelMessage.TravelPose(0.5, 64, 0.4, 0, 0),
            new Vec3d(0.5, 65.62, 0.6), new Vec3d(0.5, 65.62, 0.4));
        assertFalse(fixture.server().cancel(new TravelMessage.TravelCancel(UUID.randomUUID(), fixture.begin().generation())));
        fixture.server().unavailable(fixture.begin().chunks().getFirst());
        assertFalse(fixture.server().requestCross(crossing, 3));
        fixture = ready();
        WorldChangeTracker changes = new WorldChangeTracker();
        UUID world = UUID.randomUUID();
        fixture.server().watchWorld(changes, world);
        fixture.server().worldCleared(world);
        assertFalse(fixture.server().requestCross(crossing, 3));
        fixture = ready();
        assertTrue(fixture.server().cancel(new TravelMessage.TravelCancel(fixture.begin().token(), fixture.begin().generation())));
        assertFalse(fixture.server().requestCross(crossing, 3));
    }

    @Test
    void readyAutomaticCrossingWaitsOnlyTwoSecondsAndUnreadyRoutesStayOrdinary() {
        Fixture fixture = ready();
        assertEquals(ClientPreparedTravelServer.AutomaticCross.ORDINARY, fixture.server().automaticCross(UUID.randomUUID(), 3));
        assertEquals(ClientPreparedTravelServer.AutomaticCross.DEFER, fixture.server().automaticCross(fixture.begin().sourcePortal(), 3));
        assertEquals(ClientPreparedTravelServer.AutomaticCross.DEFER, fixture.server().automaticCross(fixture.begin().sourcePortal(), 2_002));
        assertEquals(ClientPreparedTravelServer.AutomaticCross.FALLBACK, fixture.server().automaticCross(fixture.begin().sourcePortal(), 2_003));
        assertFalse(fixture.server().readyRoute(fixture.begin().sourcePortal(), fixture.begin().expiresMillis()));
        assertEquals(ClientPreparedTravelServer.AutomaticCross.ORDINARY,
            fixture.server().automaticCross(fixture.begin().sourcePortal(), fixture.begin().expiresMillis()));
    }

    @Test
    void exactPhysicalCrossingDeclineImmediatelyReleasesOrdinaryFallback() {
        Fixture fixture = ready();
        assertEquals(ClientPreparedTravelServer.AutomaticCross.DEFER,
            fixture.server().automaticCross(fixture.begin().sourcePortal(), 3));
        assertTrue(fixture.server().cancel(new TravelMessage.TravelCancel(fixture.begin().token(), fixture.begin().generation())));
        assertEquals(ClientPreparedTravelServer.AutomaticCross.ORDINARY,
            fixture.server().automaticCross(fixture.begin().sourcePortal(), 4));
        fixture.server().begin(fixture.begin(), 5);
        assertEquals(ClientPreparedTravelServer.AutomaticCross.ORDINARY,
            fixture.server().automaticCross(fixture.begin().sourcePortal(), 6));
    }

    @Test
    void anAcknowledgedCrossingRemainsValidWhenANewerDrawableBarrierWasAcknowledged() {
        Fixture fixture = ready();
        TravelMessage.TravelCoordinate coordinate = fixture.begin().chunks().getFirst();
        fixture.server().invalidate(coordinate);
        fixture.server().column(coordinate, 2, new byte[]{2});
        List<TravelMessage> sent = new ArrayList<>();
        fixture.server().tick(3, TravelMessage.TRAVEL_FRAGMENT_BYTES, sent::add);
        TravelMessage.TravelEnd latest = (TravelMessage.TravelEnd) sent.getLast();
        assertTrue(fixture.server().ready(new TravelMessage.TravelReady(fixture.begin().token(), fixture.begin().generation(), latest.contentRevision())));
        TravelMessage.TravelCross previous = crossing(fixture, new TravelMessage.TravelPose(0.5, 64, 0.4, 0, 0),
            new Vec3d(0.5, 65.62, 0.6), new Vec3d(0.5, 65.62, 0.4));
        assertTrue(fixture.server().requestCross(previous, 4));
        assertTrue(fixture.server().validCross(previous, authority(fixture, fixture.begin().sourceGeometry(), previous.sourcePose()), 4));
    }

    @Test
    void reverseAndSprintSegmentsUseThePlayerEyeAndRejectThirdPersonCameraOffsets() {
        Fixture fixture = ready();
        TravelMessage.TravelCross reverse = crossing(fixture, new TravelMessage.TravelPose(0.5, 64, 0.8, 180, 0),
            new Vec3d(0.5, 65.62, 0.3), new Vec3d(0.5, 65.62, 0.8));
        assertTrue(fixture.server().requestCross(reverse, 3));
        assertTrue(fixture.server().validCross(reverse, authority(fixture, fixture.begin().sourceGeometry(),
            new TravelMessage.TravelPose(0.5, 64, 0.55, 180, 0)), 4));
        fixture = ready();
        TravelMessage.TravelCross sprint = crossing(fixture, new TravelMessage.TravelPose(0.5, 64, -0.2, 0, 0),
            new Vec3d(0.5, 65.62, 1.1), new Vec3d(0.5, 65.62, -0.2));
        assertTrue(fixture.server().requestCross(sprint, 3));
        assertTrue(fixture.server().validCross(sprint, new ClientPreparedTravelServer.Authority(fixture.begin().sourceWorld(),
            fixture.begin().sourceGeometry(), new TravelMessage.TravelPose(0.5, 64, 0.55, 0, 0), new Vec3d(0, 0, -0.4), 1.62), 4));
        TravelMessage.TravelCross offset = crossing(fixture, sprint.sourcePose(), sprint.previousEye(), new Vec3d(0.5, 65.62, -4.2));
        assertFalse(fixture.server().validCross(offset, authority(fixture, fixture.begin().sourceGeometry(), sprint.sourcePose()), 4));
    }

    @Test
    void horizontalCameraCrossingCanFollowAnEarlierFeetCrossingInTheSameWorld() {
        ApertureDescriptor geometry = new ApertureDescriptor(0, 64, 0, Face.U.ordinal(), true, 0, false, 1, 3,
            new long[]{7}, ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1, List.of());
        Fixture fixture = ready(geometry, ClientViewFixtures.travelBegin().world().dimension());
        assertEquals(fixture.begin().sourceWorld(), fixture.begin().world().dimension());
        assertEquals(ClientPreparedTravelServer.AutomaticCross.DEFER, fixture.server().automaticCross(fixture.begin().sourcePortal(), 3));
        TravelMessage.TravelCross crossing = crossing(fixture, new TravelMessage.TravelPose(0.5, 62.7, 0.5, 0, 90),
            new Vec3d(0.5, 64.8, 0.5), new Vec3d(0.5, 64.32, 0.5));
        assertTrue(fixture.server().requestCross(crossing, 4));
        assertTrue(fixture.server().validCross(crossing, new ClientPreparedTravelServer.Authority(fixture.begin().sourceWorld(), geometry,
            new TravelMessage.TravelPose(0.5, 63, 0.5, 0, 90), new Vec3d(0, -0.3, 0), 1.62), 4));
        assertTrue(fixture.server().takeCross().isPresent());
        assertTrue(fixture.server().commit(new ClientPreparedTravelServer.Commit(fixture.begin().sourcePortal(), fixture.begin().sourceWorld(),
            fixture.begin().world().dimension(), fixture.begin().arrival(), new Vec3d(0, -0.3, 0), 4)).isPresent());
    }

    private static TravelMessage.TravelCross crossing(Fixture fixture, TravelMessage.TravelPose pose,
                                                          Vec3d previous, Vec3d current) {
        return new TravelMessage.TravelCross(fixture.begin().token(), fixture.begin().generation(), fixture.barrier(), pose, previous, current);
    }

    private static ClientPreparedTravelServer.Authority authority(Fixture fixture, ApertureDescriptor geometry, TravelMessage.TravelPose pose) {
        return new ClientPreparedTravelServer.Authority(fixture.begin().sourceWorld(), geometry, pose, new Vec3d(0, 0, 0), 1.62);
    }

    private static Fixture ready() {
        ApertureDescriptor geometry = new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, false, 1, 3,
            new long[]{7}, ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1, List.of());
        return ready(geometry, ClientViewFixtures.travelBegin().sourceWorld());
    }

    private static Fixture ready(ApertureDescriptor geometry, String sourceWorld) {
        TravelMessage.TravelBegin sample = ClientViewFixtures.travelBegin();
        List<TravelMessage.TravelCoordinate> coordinates = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                coordinates.add(new TravelMessage.TravelCoordinate(x, z));
            }
        }
        TravelMessage.TravelBegin begin = new TravelMessage.TravelBegin(sample.token(), sample.generation(), sample.sourcePortal(),
            sourceWorld, geometry, sample.destinationToSource(), 1.0F, sample.world(), new TravelMessage.TravelPose(0, 64, 0, 0, 0),
            coordinates, sample.environment(), sample.expiresMillis(), TravelMessage.ArrivalRules.FRAME, false, 0, false);
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        server.begin(begin, 0);
        for (TravelMessage.TravelCoordinate coordinate : coordinates) {
            server.column(coordinate, 1, new byte[]{1});
        }
        List<TravelMessage> sent = new ArrayList<>();
        server.tick(1, TravelMessage.TRAVEL_FRAGMENT_BYTES * 9, sent::add);
        TravelMessage.TravelEnd end = (TravelMessage.TravelEnd) sent.getLast();
        assertTrue(server.ready(new TravelMessage.TravelReady(begin.token(), begin.generation(), end.contentRevision())));
        return new Fixture(server, begin, end.contentRevision());
    }

    private record Fixture(ClientPreparedTravelServer server, TravelMessage.TravelBegin begin, long barrier) {
    }
}
