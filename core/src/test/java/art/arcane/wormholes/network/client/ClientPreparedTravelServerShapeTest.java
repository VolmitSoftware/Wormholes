package art.arcane.wormholes.network.client;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.portal.ApertureKind;
import art.arcane.wormholes.render.client.session.ClientPreparedTravelServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientPreparedTravelServerShapeTest {
    private static final double EYE = 1.62D;
    private static final ApertureDescriptor CIRCLE = wall(ShapeDescriptor.parse("circle"));
    private static final ApertureDescriptor FULL = wall(ShapeDescriptor.FULL);

    @Test
    void aCrossingThroughTheShapeCenterIsValid() {
        Fixture fixture = ready(CIRCLE);
        TravelMessage.TravelCross crossing = crossing(fixture, 3.5D, 67.5D);
        assertTrue(fixture.server().requestCross(crossing, 3));
        assertTrue(fixture.server().validCross(crossing, authority(fixture, CIRCLE, crossing.sourcePose()), 4));
    }

    @Test
    void aCrossingThroughACornerCellOutsideTheShapeIsInvalid() {
        Fixture shaped = ready(CIRCLE);
        TravelMessage.TravelCross corner = crossing(shaped, 0.3D, 70.6D);
        assertTrue(shaped.server().requestCross(corner, 3));
        assertFalse(shaped.server().validCross(corner, authority(shaped, CIRCLE, corner.sourcePose()), 4));
        Fixture full = ready(FULL);
        TravelMessage.TravelCross open = crossing(full, 0.3D, 70.6D);
        assertTrue(full.server().requestCross(open, 3));
        assertTrue(full.server().validCross(open, authority(full, FULL, open.sourcePose()), 4));
    }

    @Test
    void aShapeChangeAfterTheBeginInvalidatesTheCrossing() {
        Fixture fixture = ready(FULL);
        TravelMessage.TravelCross crossing = crossing(fixture, 3.5D, 67.5D);
        assertTrue(fixture.server().requestCross(crossing, 3));
        assertFalse(fixture.server().validCross(crossing, authority(fixture, CIRCLE, crossing.sourcePose()), 4));
    }

    private static ApertureDescriptor wall(ShapeDescriptor shape) {
        boolean[] open = new boolean[49];
        Arrays.fill(open, true);
        return new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, false, 7, 7, ApertureDescriptor.apertureMask(7, 7, open), shape,
            0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1, List.of());
    }

    private static TravelMessage.TravelCross crossing(Fixture fixture, double x, double eyeY) {
        return new TravelMessage.TravelCross(fixture.begin().token(), fixture.begin().generation(), fixture.barrier(),
            new TravelMessage.TravelPose(x, eyeY - EYE, 0.4D, 0.0F, 0.0F), new Vec3d(x, eyeY, 0.6D), new Vec3d(x, eyeY, 0.4D));
    }

    private static ClientPreparedTravelServer.Authority authority(Fixture fixture, ApertureDescriptor geometry, TravelMessage.TravelPose pose) {
        return new ClientPreparedTravelServer.Authority(fixture.begin().sourceWorld(), geometry, pose, new Vec3d(0.0D, 0.0D, 0.0D), EYE);
    }

    private static Fixture ready(ApertureDescriptor geometry) {
        TravelMessage.TravelBegin sample = ClientViewFixtures.travelBegin();
        List<TravelMessage.TravelCoordinate> coordinates = new ArrayList<TravelMessage.TravelCoordinate>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                coordinates.add(new TravelMessage.TravelCoordinate(x, z));
            }
        }
        TravelMessage.TravelBegin begin = new TravelMessage.TravelBegin(sample.token(), sample.generation(), sample.sourcePortal(),
            sample.sourceWorld(), geometry, sample.destinationToSource(), 1.0F, sample.world(), new TravelMessage.TravelPose(0, 64, 0, 0, 0),
            coordinates, sample.environment(), sample.expiresMillis(), TravelMessage.ArrivalRules.FRAME, false, 0, false);
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        server.begin(begin, 0);
        for (TravelMessage.TravelCoordinate coordinate : coordinates) {
            server.column(coordinate, 1, new byte[]{1});
        }
        List<TravelMessage> sent = new ArrayList<TravelMessage>();
        server.tick(1, TravelMessage.TRAVEL_FRAGMENT_BYTES * 9, sent::add);
        TravelMessage.TravelEnd end = (TravelMessage.TravelEnd) sent.getLast();
        assertTrue(server.ready(new TravelMessage.TravelReady(begin.token(), begin.generation(), end.contentRevision())));
        return new Fixture(server, begin, end.contentRevision());
    }

    private record Fixture(ClientPreparedTravelServer server, TravelMessage.TravelBegin begin, long barrier) {
    }
}
