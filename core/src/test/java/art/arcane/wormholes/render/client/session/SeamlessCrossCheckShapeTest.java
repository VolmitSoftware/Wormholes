package art.arcane.wormholes.render.client.session;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.network.client.ClientViewFixtures;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.portal.ApertureKind;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SeamlessCrossCheckShapeTest {
    private static final double EYE = 1.62D;
    private static final ApertureDescriptor CIRCLE = wall(ShapeDescriptor.parse("circle"));
    private static final ApertureDescriptor FULL = wall(ShapeDescriptor.FULL);

    @Test
    void aCrossingThroughTheShapeCenterIsAccepted() {
        assertEquals(SeamlessCrossCheck.Refusal.NONE, SeamlessCrossCheck.check(walk(3.5D, 67.5D), arm(CIRCLE), server(CIRCLE, 3.5D, 67.5D)));
    }

    @Test
    void aCrossingThroughACornerCellOutsideTheShapeIsRefused() {
        assertEquals(SeamlessCrossCheck.Refusal.OUTSIDE_APERTURE,
            SeamlessCrossCheck.check(walk(0.3D, 70.6D), arm(CIRCLE), server(CIRCLE, 0.3D, 70.6D)));
        assertEquals(SeamlessCrossCheck.Refusal.NONE, SeamlessCrossCheck.check(walk(0.3D, 70.6D), arm(FULL), server(FULL, 0.3D, 70.6D)));
    }

    @Test
    void aShapeChangeAfterArmingNoLongerMatchesTheSurface() {
        assertFalse(SeamlessCrossCheck.sameSurface(FULL, CIRCLE));
        assertTrue(SeamlessCrossCheck.sameSurface(CIRCLE, CIRCLE));
        assertEquals(SeamlessCrossCheck.Refusal.CHANGED_SURFACE,
            SeamlessCrossCheck.check(walk(3.5D, 67.5D), arm(FULL), server(CIRCLE, 3.5D, 67.5D)));
    }

    private static ApertureDescriptor wall(ShapeDescriptor shape) {
        boolean[] open = new boolean[49];
        Arrays.fill(open, true);
        return new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, false, 7, 7, ApertureDescriptor.apertureMask(7, 7, open), shape,
            0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1, List.of());
    }

    private static TravelMessage.TravelCross walk(double x, double eyeY) {
        double feet = eyeY - EYE;
        return new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L, new TravelMessage.TravelPose(x, feet, 0.4D, 0.0F, 0.0F),
            new Vec3d(x, eyeY, 0.6D), new Vec3d(x, eyeY, 0.4D));
    }

    private static SeamlessCrossCheck.Server server(ApertureDescriptor geometry, double x, double eyeY) {
        return new SeamlessCrossCheck.Server("minecraft:the_nether", geometry, new TravelMessage.TravelPose(x, eyeY - EYE, 0.45D, 0.0F, 0.0F),
            new Vec3d(0.0D, 0.0D, 0.0D), EYE, false, false);
    }

    private static TravelMessage.TravelBegin arm(ApertureDescriptor geometry) {
        TravelMessage.TravelBegin sample = ClientViewFixtures.travelBegin();
        return new TravelMessage.TravelBegin(sample.token(), sample.generation(), sample.sourcePortal(), sample.sourceWorld(), geometry,
            sample.destinationToSource(), 1.0F, sample.world(), sample.arrival(), sample.environment(), TravelMessage.ArrivalRules.FRAME, true, 2);
    }
}
