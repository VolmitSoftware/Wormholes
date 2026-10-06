package art.arcane.wormholes.network.client;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.client.session.ClientPreparedTravelServer;
import art.arcane.wormholes.util.Direction;
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
        ClientViewMessage.TravelCross crossing = crossing(fixture, new ClientViewMessage.TravelPose(0.5, 64, 0.4, 0, 0),
            new GeometryVector(0.5, 65.62, 0.6), new GeometryVector(0.5, 65.62, 0.4));
        assertTrue(fixture.server().requestCross(crossing, 3));
        assertFalse(fixture.server().requestCross(crossing, 3));
        assertEquals(crossing, fixture.server().takeCross().orElseThrow());
        assertTrue(fixture.server().takeCross().isEmpty());
        assertTrue(fixture.server().validCross(crossing, authority(fixture, fixture.begin().sourceGeometry(),
            new ClientViewMessage.TravelPose(0.5, 64, 0.55, 0, 0)), 4));
        ClientViewMessage.TravelCommit commit = fixture.server().commit(new ClientPreparedTravelServer.Commit(
            fixture.begin().sourcePortal(), fixture.begin().sourceWorld(), fixture.begin().world().dimension(),
            fixture.begin().arrival(), new GeometryVector(0, 0, 0.2), 4)).orElseThrow();
        assertEquals(fixture.barrier(), commit.contentRevision());
        assertEquals(new GeometryVector(0, 0, 0.2), commit.velocity());
        assertFalse(fixture.server().requestCross(crossing, 5));
    }

    @Test
    void ordinaryDestinationChangesDoNotRevokeAnAcknowledgedDrawableCrossing() {
        Fixture fixture = ready();
        ClientViewMessage.TravelCoordinate position = fixture.begin().chunks().getFirst();
        fixture.server().invalidate(position);
        assertTrue(fixture.server().column(position, 2, new byte[]{2}));
        List<ClientViewMessage> sent = new ArrayList<>();
        fixture.server().tick(3, ClientViewProtocol.TRAVEL_FRAGMENT_BYTES, sent::add);
        assertTrue(fixture.server().ready(new ClientViewMessage.TravelReady(fixture.begin().token(), fixture.begin().generation(), fixture.barrier())));
        ClientViewMessage.TravelCross crossing = crossing(fixture, new ClientViewMessage.TravelPose(0.5, 64, 0.4, 0, 0),
            new GeometryVector(0.5, 65.62, 0.6), new GeometryVector(0.5, 65.62, 0.4));
        assertTrue(fixture.server().requestCross(crossing, 4));
        assertTrue(fixture.server().validCross(crossing, authority(fixture, fixture.begin().sourceGeometry(),
            new ClientViewMessage.TravelPose(0.5, 64, 0.55, 0, 0)), 4));
        assertEquals(fixture.barrier(), fixture.server().commit(new ClientPreparedTravelServer.Commit(
            fixture.begin().sourcePortal(), fixture.begin().sourceWorld(), fixture.begin().world().dimension(),
            fixture.begin().arrival(), new GeometryVector(0, 0, 0), 4)).orElseThrow().contentRevision());
    }

    @Test
    void unacknowledgedAndForeignTokenRevisionOrGenerationCannotCross() {
        Fixture fixture = ready();
        for (ClientViewMessage.TravelCross value : List.of(
            new ClientViewMessage.TravelCross(UUID.randomUUID(), fixture.begin().generation(), fixture.barrier(), fixture.begin().arrival(), new GeometryVector(0, 0, 0), new GeometryVector(0, 0, 0)),
            new ClientViewMessage.TravelCross(fixture.begin().token(), fixture.begin().generation() + 1, fixture.barrier(), fixture.begin().arrival(), new GeometryVector(0, 0, 0), new GeometryVector(0, 0, 0)),
            new ClientViewMessage.TravelCross(fixture.begin().token(), fixture.begin().generation(), fixture.barrier() + 1, fixture.begin().arrival(), new GeometryVector(0, 0, 0), new GeometryVector(0, 0, 0)))) {
            assertFalse(fixture.server().requestCross(value, 3));
        }
        assertTrue(fixture.server().takeCross().isEmpty());
    }

    @Test
    void observedMovementEyeHeightAndActualApertureAreRequired() {
        Fixture fixture = ready();
        ClientViewMessage.TravelCross valid = crossing(fixture, new ClientViewMessage.TravelPose(0.5, 64, 0.4, 0, 0),
            new GeometryVector(0.5, 65.62, 0.6), new GeometryVector(0.5, 65.62, 0.4));
        assertTrue(fixture.server().requestCross(valid, 3));
        assertFalse(fixture.server().validCross(valid, authority(fixture, fixture.begin().sourceGeometry(),
            new ClientViewMessage.TravelPose(10, 64, 0.55, 0, 0)), 4));
        assertFalse(fixture.server().validCross(valid, new ClientPreparedTravelServer.Authority("other:world", fixture.begin().sourceGeometry(),
            new ClientViewMessage.TravelPose(0.5, 64, 0.55, 0, 0), new GeometryVector(0, 0, 0), 1.62), 4));
        assertFalse(fixture.server().validCross(crossing(fixture, valid.sourcePose(), valid.previousEye(), new GeometryVector(0.5, 66, 0.4)),
            authority(fixture, fixture.begin().sourceGeometry(), valid.sourcePose()), 4));
        assertFalse(fixture.server().validCross(crossing(fixture, new ClientViewMessage.TravelPose(2, 64, 0.4, 0, 0),
            new GeometryVector(2, 65.62, 0.6), new GeometryVector(2, 65.62, 0.4)),
            authority(fixture, fixture.begin().sourceGeometry(), new ClientViewMessage.TravelPose(2, 64, 0.55, 0, 0)), 4));
        assertFalse(fixture.server().validCross(valid, authority(fixture, fixture.begin().sourceGeometry(), valid.sourcePose()), 2_003));
    }

    @Test
    void unloadWorldInvalidationAndScopedCancellationRevokeCrossing() {
        Fixture fixture = ready();
        ClientViewMessage.TravelCross crossing = crossing(fixture, new ClientViewMessage.TravelPose(0.5, 64, 0.4, 0, 0),
            new GeometryVector(0.5, 65.62, 0.6), new GeometryVector(0.5, 65.62, 0.4));
        assertFalse(fixture.server().cancel(new ClientViewMessage.TravelCancel(UUID.randomUUID(), fixture.begin().generation())));
        fixture.server().unavailable(fixture.begin().chunks().getFirst());
        assertFalse(fixture.server().requestCross(crossing, 3));
        fixture = ready();
        ProjectionWorldChangeTracker changes = new ProjectionWorldChangeTracker();
        UUID world = UUID.randomUUID();
        fixture.server().watchWorld(changes, world);
        fixture.server().worldCleared(world);
        assertFalse(fixture.server().requestCross(crossing, 3));
        fixture = ready();
        assertTrue(fixture.server().cancel(new ClientViewMessage.TravelCancel(fixture.begin().token(), fixture.begin().generation())));
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
        assertTrue(fixture.server().cancel(new ClientViewMessage.TravelCancel(fixture.begin().token(), fixture.begin().generation())));
        assertEquals(ClientPreparedTravelServer.AutomaticCross.ORDINARY,
            fixture.server().automaticCross(fixture.begin().sourcePortal(), 4));
        fixture.server().begin(fixture.begin(), 5);
        assertEquals(ClientPreparedTravelServer.AutomaticCross.ORDINARY,
            fixture.server().automaticCross(fixture.begin().sourcePortal(), 6));
    }

    @Test
    void anAcknowledgedCrossingRemainsValidWhenANewerDrawableBarrierWasAcknowledged() {
        Fixture fixture = ready();
        ClientViewMessage.TravelCoordinate coordinate = fixture.begin().chunks().getFirst();
        fixture.server().invalidate(coordinate);
        fixture.server().column(coordinate, 2, new byte[]{2});
        List<ClientViewMessage> sent = new ArrayList<>();
        fixture.server().tick(3, ClientViewProtocol.TRAVEL_FRAGMENT_BYTES, sent::add);
        ClientViewMessage.TravelEnd latest = (ClientViewMessage.TravelEnd) sent.getLast();
        assertTrue(fixture.server().ready(new ClientViewMessage.TravelReady(fixture.begin().token(), fixture.begin().generation(), latest.contentRevision())));
        ClientViewMessage.TravelCross previous = crossing(fixture, new ClientViewMessage.TravelPose(0.5, 64, 0.4, 0, 0),
            new GeometryVector(0.5, 65.62, 0.6), new GeometryVector(0.5, 65.62, 0.4));
        assertTrue(fixture.server().requestCross(previous, 4));
        assertTrue(fixture.server().validCross(previous, authority(fixture, fixture.begin().sourceGeometry(), previous.sourcePose()), 4));
    }

    @Test
    void reverseAndSprintSegmentsUseThePlayerEyeAndRejectThirdPersonCameraOffsets() {
        Fixture fixture = ready();
        ClientViewMessage.TravelCross reverse = crossing(fixture, new ClientViewMessage.TravelPose(0.5, 64, 0.8, 180, 0),
            new GeometryVector(0.5, 65.62, 0.3), new GeometryVector(0.5, 65.62, 0.8));
        assertTrue(fixture.server().requestCross(reverse, 3));
        assertTrue(fixture.server().validCross(reverse, authority(fixture, fixture.begin().sourceGeometry(),
            new ClientViewMessage.TravelPose(0.5, 64, 0.55, 180, 0)), 4));
        fixture = ready();
        ClientViewMessage.TravelCross sprint = crossing(fixture, new ClientViewMessage.TravelPose(0.5, 64, -0.2, 0, 0),
            new GeometryVector(0.5, 65.62, 1.1), new GeometryVector(0.5, 65.62, -0.2));
        assertTrue(fixture.server().requestCross(sprint, 3));
        assertTrue(fixture.server().validCross(sprint, new ClientPreparedTravelServer.Authority(fixture.begin().sourceWorld(),
            fixture.begin().sourceGeometry(), new ClientViewMessage.TravelPose(0.5, 64, 0.55, 0, 0), new GeometryVector(0, 0, -0.4), 1.62), 4));
        ClientViewMessage.TravelCross offset = crossing(fixture, sprint.sourcePose(), sprint.previousEye(), new GeometryVector(0.5, 65.62, -4.2));
        assertFalse(fixture.server().validCross(offset, authority(fixture, fixture.begin().sourceGeometry(), sprint.sourcePose()), 4));
    }

    @Test
    void horizontalCameraCrossingCanFollowAnEarlierFeetCrossingInTheSameWorld() {
        ClientPortalGeometry geometry = new ClientPortalGeometry(0, 64, 0, Direction.U.ordinal(), true, 0, false, 1, 3,
            new long[]{7}, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ClientPortalGeometry.KIND_FRAME, 0.0D, 0, 1, List.of());
        Fixture fixture = ready(geometry, ClientViewFixtures.travelBegin().world().dimension());
        assertEquals(fixture.begin().sourceWorld(), fixture.begin().world().dimension());
        assertEquals(ClientPreparedTravelServer.AutomaticCross.DEFER, fixture.server().automaticCross(fixture.begin().sourcePortal(), 3));
        ClientViewMessage.TravelCross crossing = crossing(fixture, new ClientViewMessage.TravelPose(0.5, 62.7, 0.5, 0, 90),
            new GeometryVector(0.5, 64.8, 0.5), new GeometryVector(0.5, 64.32, 0.5));
        assertTrue(fixture.server().requestCross(crossing, 4));
        assertTrue(fixture.server().validCross(crossing, new ClientPreparedTravelServer.Authority(fixture.begin().sourceWorld(), geometry,
            new ClientViewMessage.TravelPose(0.5, 63, 0.5, 0, 90), new GeometryVector(0, -0.3, 0), 1.62), 4));
        assertTrue(fixture.server().takeCross().isPresent());
        assertTrue(fixture.server().commit(new ClientPreparedTravelServer.Commit(fixture.begin().sourcePortal(), fixture.begin().sourceWorld(),
            fixture.begin().world().dimension(), fixture.begin().arrival(), new GeometryVector(0, -0.3, 0), 4)).isPresent());
    }

    private static ClientViewMessage.TravelCross crossing(Fixture fixture, ClientViewMessage.TravelPose pose,
                                                          GeometryVector previous, GeometryVector current) {
        return new ClientViewMessage.TravelCross(fixture.begin().token(), fixture.begin().generation(), fixture.barrier(), pose, previous, current);
    }

    private static ClientPreparedTravelServer.Authority authority(Fixture fixture, ClientPortalGeometry geometry, ClientViewMessage.TravelPose pose) {
        return new ClientPreparedTravelServer.Authority(fixture.begin().sourceWorld(), geometry, pose, new GeometryVector(0, 0, 0), 1.62);
    }

    private static Fixture ready() {
        ClientPortalGeometry geometry = new ClientPortalGeometry(0, 64, 0, Direction.S.ordinal(), true, 0, false, 1, 3,
            new long[]{7}, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ClientPortalGeometry.KIND_FRAME, 0.0D, 0, 1, List.of());
        return ready(geometry, ClientViewFixtures.travelBegin().sourceWorld());
    }

    private static Fixture ready(ClientPortalGeometry geometry, String sourceWorld) {
        ClientViewMessage.TravelBegin sample = ClientViewFixtures.travelBegin();
        List<ClientViewMessage.TravelCoordinate> coordinates = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                coordinates.add(new ClientViewMessage.TravelCoordinate(x, z));
            }
        }
        ClientViewMessage.TravelBegin begin = new ClientViewMessage.TravelBegin(sample.token(), sample.generation(), sample.sourcePortal(),
            sourceWorld, geometry, sample.destinationToSource(), sample.world(), new ClientViewMessage.TravelPose(0, 64, 0, 0, 0),
            coordinates, sample.environment(), sample.expiresMillis());
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        server.begin(begin, 0);
        for (ClientViewMessage.TravelCoordinate coordinate : coordinates) {
            server.column(coordinate, 1, new byte[]{1});
        }
        List<ClientViewMessage> sent = new ArrayList<>();
        server.tick(1, ClientViewProtocol.TRAVEL_FRAGMENT_BYTES * 9, sent::add);
        ClientViewMessage.TravelEnd end = (ClientViewMessage.TravelEnd) sent.getLast();
        assertTrue(server.ready(new ClientViewMessage.TravelReady(begin.token(), begin.generation(), end.contentRevision())));
        return new Fixture(server, begin, end.contentRevision());
    }

    private record Fixture(ClientPreparedTravelServer server, ClientViewMessage.TravelBegin begin, long barrier) {
    }
}
