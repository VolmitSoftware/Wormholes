package art.arcane.wormholes.network.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.portal.ApertureKind;
import art.arcane.wormholes.render.client.session.ClientPreparedTravelServer;
import art.arcane.wormholes.render.client.session.SeamlessCrossCheck;

final class ClientPreparedTravelServerScaleTest {
    private static final double EYE = 1.62D;
    private static final ApertureDescriptor WALL = wall();

    @Test
    void preparedCrossingToleranceGrowsWithTheTravelScale() {
        Fixture rigid = ready(1.0F);
        TravelMessage.TravelCross rigidCross = crossing(rigid.begin(), rigid.barrier(), 3.5D, 67.5D);
        assertTrue(rigid.server().requestCross(rigidCross, 3));
        assertFalse(rigid.server().validCross(rigidCross, authority(rigid.begin(), 3.5D, 67.5D - EYE, 1.5D, EYE), 4));

        Fixture grown = ready(3.0F);
        TravelMessage.TravelCross grownCross = crossing(grown.begin(), grown.barrier(), 3.5D, 67.5D);
        assertTrue(grown.server().requestCross(grownCross, 3));
        assertTrue(grown.server().validCross(grownCross, authority(grown.begin(), 3.5D, 67.5D - EYE, 1.5D, EYE), 4));
    }

    @Test
    void shrinkingPairsKeepTheBaseTolerance() {
        Fixture shrunk = ready(1.0F / 3.0F);
        TravelMessage.TravelCross cross = crossing(shrunk.begin(), shrunk.barrier(), 3.5D, 67.5D);
        assertTrue(shrunk.server().requestCross(cross, 3));
        assertFalse(shrunk.server().validCross(cross, authority(shrunk.begin(), 3.5D, 67.5D - EYE, 1.5D, EYE), 4));
    }

    @Test
    void grownTravellersCanStillCross() {
        double eye = EYE * 3.0D;
        Fixture fixture = ready(1.0F);
        TravelMessage.TravelCross cross = new TravelMessage.TravelCross(fixture.begin().token(), fixture.begin().generation(), fixture.barrier(),
            new TravelMessage.TravelPose(3.5D, 64.0D, 0.4D, 0.0F, 0.0F), new Vec3d(3.5D, 64.0D + eye, 0.6D), new Vec3d(3.5D, 64.0D + eye, 0.4D));
        assertTrue(fixture.server().requestCross(cross, 3));
        assertTrue(fixture.server().validCross(cross, authority(fixture.begin(), 3.5D, 64.0D, 0.0D, eye), 4));
    }

    @Test
    void seamlessCrossToleranceGrowsWithTheTravelScale() {
        TravelMessage.TravelCross cross = new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L,
            new TravelMessage.TravelPose(3.5D, 67.5D - EYE, 0.4D, 0.0F, 0.0F), new Vec3d(3.5D, 67.5D, 0.6D), new Vec3d(3.5D, 67.5D, 0.4D));
        SeamlessCrossCheck.Server server = new SeamlessCrossCheck.Server("minecraft:the_nether", WALL,
            new TravelMessage.TravelPose(3.5D, 67.5D - EYE, 6.0D, 0.0F, 0.0F), new Vec3d(0.0D, 0.0D, 0.0D), EYE, false, false);

        assertEquals(SeamlessCrossCheck.Refusal.FAR_FROM_SERVER, SeamlessCrossCheck.check(cross, arm(1.0F), server));
        assertEquals(SeamlessCrossCheck.Refusal.NONE, SeamlessCrossCheck.check(cross, arm(3.0F), server));
    }

    @Test
    void seamlessCrossAcceptsGrownEyeHeights() {
        double eye = EYE * 3.0D;
        TravelMessage.TravelCross cross = new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L,
            new TravelMessage.TravelPose(3.5D, 64.0D, 0.4D, 0.0F, 0.0F), new Vec3d(3.5D, 64.0D + eye, 0.6D), new Vec3d(3.5D, 64.0D + eye, 0.4D));
        SeamlessCrossCheck.Server server = new SeamlessCrossCheck.Server("minecraft:the_nether", WALL,
            new TravelMessage.TravelPose(3.5D, 64.0D, 0.45D, 0.0F, 0.0F), new Vec3d(0.0D, 0.0D, 0.0D), eye, false, false);

        assertEquals(SeamlessCrossCheck.Refusal.NONE, SeamlessCrossCheck.check(cross, arm(1.0F), server));
    }

    private static TravelMessage.TravelBegin arm(float scale) {
        TravelMessage.TravelBegin sample = ClientViewFixtures.travelBegin();
        return new TravelMessage.TravelBegin(sample.token(), sample.generation(), sample.sourcePortal(), "minecraft:the_nether", WALL,
            sample.destinationToSource(), scale, sample.world(), sample.arrival(), sample.chunks(), sample.environment(), sample.expiresMillis(),
            TravelMessage.ArrivalRules.FRAME, true, 1, true);
    }

    private static ApertureDescriptor wall() {
        boolean[] open = new boolean[49];
        Arrays.fill(open, true);
        return new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, false, 7, 7, ApertureDescriptor.apertureMask(7, 7, open),
            ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1, List.of());
    }

    private static TravelMessage.TravelCross crossing(TravelMessage.TravelBegin begin, long barrier, double x, double eyeY) {
        return new TravelMessage.TravelCross(begin.token(), begin.generation(), barrier, new TravelMessage.TravelPose(x, eyeY - EYE, 0.4D, 0.0F, 0.0F),
            new Vec3d(x, eyeY, 0.6D), new Vec3d(x, eyeY, 0.4D));
    }

    private static ClientPreparedTravelServer.Authority authority(TravelMessage.TravelBegin begin, double x, double feetY, double offset, double eye) {
        return new ClientPreparedTravelServer.Authority(begin.sourceWorld(), WALL, new TravelMessage.TravelPose(x + offset, feetY, 0.4D, 0.0F, 0.0F),
            new Vec3d(0.0D, 0.0D, 0.0D), eye);
    }

    private static Fixture ready(float scale) {
        TravelMessage.TravelBegin sample = ClientViewFixtures.travelBegin();
        List<TravelMessage.TravelCoordinate> coordinates = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                coordinates.add(new TravelMessage.TravelCoordinate(x, z));
            }
        }
        TravelMessage.TravelBegin begin = new TravelMessage.TravelBegin(sample.token(), sample.generation(), sample.sourcePortal(),
            sample.sourceWorld(), WALL, sample.destinationToSource(), scale, sample.world(), new TravelMessage.TravelPose(0, 64, 0, 0, 0),
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
