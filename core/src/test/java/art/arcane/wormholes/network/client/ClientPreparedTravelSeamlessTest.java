package art.arcane.wormholes.network.client;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.portal.ApertureKind;
import art.arcane.wormholes.render.client.session.ClientPreparedTravelServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPreparedTravelSeamlessTest {
    private static final ApertureDescriptor GEOMETRY = new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, false, 1, 3,
        new long[]{7}, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1, List.of());
    private static final ClientPreparedTravelServer.SeamlessAuthority STANDING =
        new ClientPreparedTravelServer.SeamlessAuthority(false, false, 100L, 1_000L);

    @Test
    void routedColumnsCompleteTheBarrierWithoutChunkFragments() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        TravelMessage.TravelBegin begin = begin(true);
        server.begin(begin, 0);
        List<TravelMessage> sent = new ArrayList<>();
        server.tick(1, TravelMessage.TRAVEL_FRAGMENT_BYTES * 9, sent::add);
        assertEquals(List.of(begin), sent);
        for (TravelMessage.TravelCoordinate coordinate : begin.chunks()) {
            assertTrue(server.routed(coordinate, 1));
        }
        sent.clear();
        server.tick(2, TravelMessage.TRAVEL_FRAGMENT_BYTES * 9, sent::add);
        assertEquals(1, sent.size());
        TravelMessage.TravelEnd end = (TravelMessage.TravelEnd) sent.getFirst();
        assertEquals(begin.chunks().size(), end.chunks().size());
        assertTrue(server.ready(new TravelMessage.TravelReady(begin.token(), begin.generation(), end.contentRevision())));
        assertTrue(server.routed(begin.chunks().getFirst(), 1));
        sent.clear();
        server.tick(3, TravelMessage.TRAVEL_FRAGMENT_BYTES * 9, sent::add);
        assertTrue(sent.isEmpty());
        assertTrue(server.routed(begin.chunks().getFirst(), 2));
        server.tick(4, TravelMessage.TRAVEL_FRAGMENT_BYTES * 9, sent::add);
        assertTrue(((TravelMessage.TravelEnd) sent.getLast()).contentRevision() > end.contentRevision());
    }

    @Test
    void routedColumnsAreRefusedForPreparedTravel() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        TravelMessage.TravelBegin begin = begin(false);
        server.begin(begin, 0);
        assertFalse(server.routed(begin.chunks().getFirst(), 1));
    }

    @Test
    void acceptCarriesTheResidentHandleAndEndsThePreparation() {
        Fixture fixture = ready();
        TravelMessage.TravelCross crossing = crossing(fixture, 0.0F);
        assertTrue(fixture.server().requestCross(crossing, 3));
        assertTrue(fixture.server().takeCross().isPresent());
        TravelMessage.TravelAccept accept = fixture.server().accept(commit(fixture, 4), 3, true, 77L).orElseThrow();
        assertEquals(fixture.begin().token(), accept.token());
        assertEquals(fixture.begin().generation(), accept.generation());
        assertEquals(fixture.barrier(), accept.contentRevision());
        assertEquals(3, accept.levelHandle());
        assertTrue(accept.dimensionChanged());
        assertEquals(77L, accept.serverTick());
        assertEquals(fixture.begin().arrival(), accept.pose());
        assertTrue(fixture.server().preparing().isEmpty());
        assertTrue(fixture.server().accept(commit(fixture, 4), 3, true, 77L).isEmpty());
    }

    @Test
    void validSeamlessCrossingPassesAndEachNewRejectionIsReported() {
        Fixture fixture = ready();
        TravelMessage.TravelCross crossing = crossing(fixture, 0.0F);
        assertTrue(fixture.server().requestCross(crossing, 3));
        assertEquals(ClientPreparedTravelServer.SeamlessRejection.NONE,
            fixture.server().validSeamlessCross(crossing, authority(fixture), STANDING, 4));
        assertEquals(ClientPreparedTravelServer.SeamlessRejection.AWAITING_TELEPORT, fixture.server().validSeamlessCross(crossing, authority(fixture),
            new ClientPreparedTravelServer.SeamlessAuthority(true, false, 100L, 1_000L), 4));
        assertEquals(ClientPreparedTravelServer.SeamlessRejection.CHANGING_DIMENSION, fixture.server().validSeamlessCross(crossing, authority(fixture),
            new ClientPreparedTravelServer.SeamlessAuthority(false, true, 100L, 1_000L), 4));
        assertEquals(ClientPreparedTravelServer.SeamlessRejection.CROSSING, fixture.server().validSeamlessCross(crossing,
            new ClientPreparedTravelServer.Authority("other:world", GEOMETRY, crossing.sourcePose(), new Vec3d(0, 0, 0), 1.62), STANDING, 4));
    }

    @Test
    void crossingMadeWhileTurningIsAcceptedWhateverTheFrameLook() {
        for (float lookYaw : new float[]{6.0F, 40.0F, -170.0F}) {
            Fixture fixture = ready();
            TravelMessage.TravelCross turning = crossing(fixture, lookYaw);
            assertTrue(fixture.server().requestCross(turning, 3));
            assertEquals(ClientPreparedTravelServer.SeamlessRejection.NONE,
                fixture.server().validSeamlessCross(turning, authority(fixture), STANDING, 4));
        }
    }

    @Test
    void cooldownAndComboLimitSurviveNewPreparations() {
        ClientPreparedTravelServer server = ready().server();
        server.seamlessCrossed(100L, 1_000L);
        Fixture fixture = ready(server);
        TravelMessage.TravelCross crossing = crossing(fixture, 0.0F);
        assertTrue(fixture.server().requestCross(crossing, 1_500));
        assertEquals(ClientPreparedTravelServer.SeamlessRejection.COOLDOWN, fixture.server().validSeamlessCross(crossing, authority(fixture),
            new ClientPreparedTravelServer.SeamlessAuthority(false, false, 110L, 1_000L), 1_500));
        assertEquals(ClientPreparedTravelServer.SeamlessRejection.NONE, fixture.server().validSeamlessCross(crossing, authority(fixture),
            new ClientPreparedTravelServer.SeamlessAuthority(false, false, 110L, 1_000L), 2_000));
        server.seamlessCrossed(111L, 2_001L);
        server.seamlessCrossed(112L, 2_002L);
        assertEquals(ClientPreparedTravelServer.SeamlessRejection.COMBO, fixture.server().validSeamlessCross(crossing, authority(fixture),
            new ClientPreparedTravelServer.SeamlessAuthority(false, false, 115L, 0L), 2_003));
        assertEquals(ClientPreparedTravelServer.SeamlessRejection.NONE, fixture.server().validSeamlessCross(crossing, authority(fixture),
            new ClientPreparedTravelServer.SeamlessAuthority(false, false, 131L, 0L), 2_003));
    }

    private static ClientPreparedTravelServer.Commit commit(Fixture fixture, long now) {
        return new ClientPreparedTravelServer.Commit(fixture.begin().sourcePortal(), fixture.begin().sourceWorld(),
            fixture.begin().world().dimension(), fixture.begin().arrival(), new Vec3d(0, 0, 0.2), now);
    }

    private static TravelMessage.TravelCross crossing(Fixture fixture, float lookYaw) {
        return new TravelMessage.TravelCross(fixture.begin().token(), fixture.begin().generation(), fixture.barrier(),
            new TravelMessage.TravelPose(0.5, 64, 0.4, lookYaw, 0), new Vec3d(0.5, 65.62, 0.6), new Vec3d(0.5, 65.62, 0.4));
    }

    private static ClientPreparedTravelServer.Authority authority(Fixture fixture) {
        return new ClientPreparedTravelServer.Authority(fixture.begin().sourceWorld(), GEOMETRY,
            new TravelMessage.TravelPose(0.5, 64, 0.55, 0, 0), new Vec3d(0, 0, 0), 1.62);
    }

    private static TravelMessage.TravelBegin begin(boolean seamless) {
        TravelMessage.TravelBegin sample = ClientViewFixtures.travelBegin();
        List<TravelMessage.TravelCoordinate> coordinates = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                coordinates.add(new TravelMessage.TravelCoordinate(x, z));
            }
        }
        return new TravelMessage.TravelBegin(sample.token(), sample.generation(), sample.sourcePortal(), sample.sourceWorld(), GEOMETRY,
            sample.destinationToSource(), sample.world(), new TravelMessage.TravelPose(0, 64, 0, 0, 0), coordinates, sample.environment(),
            sample.expiresMillis(), TravelMessage.ArrivalRules.FRAME, seamless, seamless ? 3 : 0, seamless);
    }

    private static Fixture ready() {
        return ready(new ClientPreparedTravelServer());
    }

    private static Fixture ready(ClientPreparedTravelServer server) {
        TravelMessage.TravelBegin begin = begin(true);
        server.begin(begin, 0);
        for (TravelMessage.TravelCoordinate coordinate : begin.chunks()) {
            server.routed(coordinate, 1);
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
