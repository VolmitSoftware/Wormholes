package art.arcane.wormholes.render.client.session;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.network.client.ClientViewFixtures;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.portal.ApertureKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeamlessCrossCheckTest {
    private static final ApertureDescriptor GEOMETRY = new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, false, 1, 3,
        new long[]{7}, ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1, List.of());
    private static final ApertureDescriptor BACK = new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), false, 0, false, 1, 3,
        new long[]{7}, ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1, List.of());
    private static final ApertureDescriptor FLOOR = new ApertureDescriptor(0, 60, 0, Face.U.ordinal(), true, 0, false, 3, 3,
        new long[]{511}, ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1, List.of());
    private static final double EYE = 1.62D;

    @Test
    void walkingThroughTheApertureFromTheArmedSideIsAccepted() {
        assertEquals(SeamlessCrossCheck.Refusal.NONE, SeamlessCrossCheck.check(walk(0.5D), arm(GEOMETRY), server(GEOMETRY, 0.55D, 0.0D)));
    }

    @Test
    void everyCrossingIsJudgedOnItsOwnGeometryWithoutACooldown() {
        TravelMessage.TravelBegin arm = arm(GEOMETRY);
        for (int crossing = 0; crossing < 20; crossing++) {
            assertEquals(SeamlessCrossCheck.Refusal.NONE, SeamlessCrossCheck.check(walk(0.5D), arm, server(GEOMETRY, 0.55D, 0.0D)));
        }
    }

    @Test
    void terminalVelocityFallsThroughAFloorPortalAreAccepted() {
        TravelMessage.TravelPose claimed = new TravelMessage.TravelPose(1.5D, 58.4D, 1.5D, 0.0F, 90.0F);
        TravelMessage.TravelCross fall = new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L, claimed,
            new Vec3d(1.5D, 60.6D, 1.5D), new Vec3d(1.5D, 58.4D + EYE, 1.5D));
        SeamlessCrossCheck.Server server = new SeamlessCrossCheck.Server("minecraft:the_nether", FLOOR,
            new TravelMessage.TravelPose(1.5D, 61.9D, 1.5D, 0.0F, 90.0F), new Vec3d(0.0D, -3.92D, 0.0D), EYE, false, false);
        assertEquals(SeamlessCrossCheck.Refusal.NONE, SeamlessCrossCheck.check(fall, arm(FLOOR), server));
    }

    @Test
    void aClaimFarFromTheServerPositionIsRefused() {
        assertEquals(SeamlessCrossCheck.Refusal.FAR_FROM_SERVER,
            SeamlessCrossCheck.check(walk(0.5D), arm(GEOMETRY), server(GEOMETRY, 6.0D, 0.0D)));
    }

    @Test
    void aClaimOneFastTickBehindTheServerIsAccepted() {
        assertEquals(SeamlessCrossCheck.Refusal.NONE, SeamlessCrossCheck.check(walk(0.5D), arm(GEOMETRY), server(GEOMETRY, 3.5D, 0.0D)));
    }

    @Test
    void crossingTowardTheArmedSideIsRefused() {
        TravelMessage.TravelCross backwards = new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L,
            new TravelMessage.TravelPose(0.5D, 64.0D, 0.6D, 0.0F, 0.0F), new Vec3d(0.5D, 64.0D + EYE, 0.4D), new Vec3d(0.5D, 64.0D + EYE, 0.6D));
        assertEquals(SeamlessCrossCheck.Refusal.WRONG_SIDE, SeamlessCrossCheck.check(backwards, arm(GEOMETRY), server(GEOMETRY, 0.5D, 0.0D)));
    }

    @Test
    void stoppingShortOfThePlaneIsRefused() {
        TravelMessage.TravelCross shy = new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L,
            new TravelMessage.TravelPose(0.5D, 64.0D, 0.55D, 0.0F, 0.0F), new Vec3d(0.5D, 64.0D + EYE, 0.7D), new Vec3d(0.5D, 64.0D + EYE, 0.55D));
        assertEquals(SeamlessCrossCheck.Refusal.NO_CROSSING, SeamlessCrossCheck.check(shy, arm(GEOMETRY), server(GEOMETRY, 0.6D, 0.0D)));
    }

    @Test
    void passingBesideTheApertureIsRefused() {
        assertEquals(SeamlessCrossCheck.Refusal.OUTSIDE_APERTURE,
            SeamlessCrossCheck.check(walk(5.5D), arm(GEOMETRY), new SeamlessCrossCheck.Server("minecraft:the_nether", GEOMETRY,
                new TravelMessage.TravelPose(5.5D, 64.0D, 0.55D, 0.0F, 0.0F), new Vec3d(0, 0, 0), EYE, false, false)));
    }

    @Test
    void aVanillaTeleportInFlightRefusesTheCrossing() {
        SeamlessCrossCheck.Server awaiting = new SeamlessCrossCheck.Server("minecraft:the_nether", GEOMETRY,
            new TravelMessage.TravelPose(0.5D, 64.0D, 0.55D, 0.0F, 0.0F), new Vec3d(0, 0, 0), EYE, true, false);
        assertEquals(SeamlessCrossCheck.Refusal.AWAITING_TELEPORT, SeamlessCrossCheck.check(walk(0.5D), arm(GEOMETRY), awaiting));
    }

    @Test
    void aPortalSeenFromItsOtherSideNoLongerMatchesTheArm() {
        assertEquals(SeamlessCrossCheck.Refusal.CHANGED_SURFACE, SeamlessCrossCheck.check(walk(0.5D), arm(GEOMETRY), server(BACK, 0.55D, 0.0D)));
    }

    @Test
    void anEyeDetachedFromTheClaimedFeetIsRefused() {
        TravelMessage.TravelCross detached = new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L,
            new TravelMessage.TravelPose(0.5D, 64.0D, 0.4D, 0.0F, 0.0F), new Vec3d(0.5D, 64.0D + EYE, 0.6D), new Vec3d(0.5D, 69.0D, 0.4D));
        assertEquals(SeamlessCrossCheck.Refusal.INCONSISTENT_EYE, SeamlessCrossCheck.check(detached, arm(GEOMETRY), server(GEOMETRY, 0.55D, 0.0D)));
    }

    @Test
    void crouchingEyeHeightsStillPass() {
        TravelMessage.TravelCross crouched = new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L,
            new TravelMessage.TravelPose(0.5D, 64.0D, 0.4D, 0.0F, 0.0F), new Vec3d(0.5D, 65.27D, 0.6D), new Vec3d(0.5D, 65.27D, 0.4D));
        assertEquals(SeamlessCrossCheck.Refusal.NONE, SeamlessCrossCheck.check(crouched, arm(GEOMETRY), server(GEOMETRY, 0.55D, 0.0D)));
    }

    private static TravelMessage.TravelCross walk(double x) {
        return new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L, new TravelMessage.TravelPose(x, 64.0D, 0.4D, 0.0F, 0.0F),
            new Vec3d(x, 64.0D + EYE, 0.6D), new Vec3d(x, 64.0D + EYE, 0.4D));
    }

    private static SeamlessCrossCheck.Server server(ApertureDescriptor geometry, double z, double speed) {
        return new SeamlessCrossCheck.Server("minecraft:the_nether", geometry, new TravelMessage.TravelPose(0.5D, 64.0D, z, 0.0F, 0.0F),
            new Vec3d(0.0D, 0.0D, -speed), EYE, false, false);
    }

    private static TravelMessage.TravelBegin arm(ApertureDescriptor geometry) {
        TravelMessage.TravelBegin sample = ClientViewFixtures.travelBegin();
        return new TravelMessage.TravelBegin(sample.token(), sample.generation(), sample.sourcePortal(), sample.sourceWorld(), geometry,
            sample.destinationToSource(), 1.0F, sample.world(), sample.arrival(), sample.chunks(), sample.environment(), sample.expiresMillis(),
            TravelMessage.ArrivalRules.FRAME, true, 2, true);
    }
}
