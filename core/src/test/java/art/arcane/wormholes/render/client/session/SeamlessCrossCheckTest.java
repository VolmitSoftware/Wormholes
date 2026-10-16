package art.arcane.wormholes.render.client.session;

import art.arcane.optics.crossing.GravityFrame;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.network.client.ClientViewFixtures;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.portal.ApertureKind;
import art.arcane.wormholes.portal.vanilla.EndPortalContact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeamlessCrossCheckTest {
    private static final ApertureDescriptor GEOMETRY = new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, false, 1, 3,
        new long[]{7}, ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1, List.of());
    private static final ApertureDescriptor BACK = new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), false, 0, false, 1, 3,
        new long[]{7}, ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1, List.of());
    private static final ApertureDescriptor FLOOR = new ApertureDescriptor(0, 60, 0, Face.U.ordinal(), true, 0, false, 3, 3,
        new long[]{511}, ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1, List.of());
    private static final ApertureDescriptor END = new ApertureDescriptor(10, 64, 10, Face.D.ordinal(), false, 0, false, 3, 3,
        new long[]{511}, ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.VANILLA_REPLACEMENT, 0.0D, 0, 1, List.of());
    private static final ApertureDescriptor FLOOR_OPENING = new ApertureDescriptor(0, 64, 0, Face.D.ordinal(), false, 0, false, 3, 3,
        new long[]{511}, ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1, List.of());
    private static final double EYE = 1.62D;
    private static final Predicate<Box> OPEN = box -> false;
    private static final Predicate<Box> BACKING = box -> box.getYa() < 64.0D;

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
            new TravelMessage.TravelPose(1.5D, 61.9D, 1.5D, 0.0F, 90.0F), new Vec3d(0.0D, -3.92D, 0.0D), EYE, bounds(1.5D, 61.9D, 1.5D), GravityFrame.WORLD, false, false, OPEN);
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
                new TravelMessage.TravelPose(5.5D, 64.0D, 0.55D, 0.0F, 0.0F), new Vec3d(0, 0, 0), EYE, bounds(5.5D, 64.0D, 0.55D), GravityFrame.WORLD, false, false, OPEN)));
    }

    @Test
    void aVanillaTeleportInFlightRefusesTheCrossing() {
        SeamlessCrossCheck.Server awaiting = new SeamlessCrossCheck.Server("minecraft:the_nether", GEOMETRY,
            new TravelMessage.TravelPose(0.5D, 64.0D, 0.55D, 0.0F, 0.0F), new Vec3d(0, 0, 0), EYE, bounds(0.5D, 64.0D, 0.55D), GravityFrame.WORLD, true, false, OPEN);
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

    @Test
    void standingOnTheFloorInsideAnEndPortalIsAcceptedWithoutTheEyeCrossingItsPlane() {
        assertEquals(SeamlessCrossCheck.Refusal.NONE, SeamlessCrossCheck.check(stand(11.5D, 64.0D), arm(END, EndPortalContact.END),
            standing(END, 11.5D, 64.0D)));
        assertEquals(SeamlessCrossCheck.Refusal.NONE, SeamlessCrossCheck.check(stand(11.5D, 64.7D), arm(END, EndPortalContact.END),
            standing(END, 11.5D, 64.8125D)));
    }

    @Test
    void anEndPortalClaimWithoutContactIsRefused() {
        assertEquals(SeamlessCrossCheck.Refusal.NO_CONTACT, SeamlessCrossCheck.check(stand(11.5D, 64.8125D), arm(END, EndPortalContact.END),
            standing(END, 11.5D, 64.8125D)));
        assertEquals(SeamlessCrossCheck.Refusal.NO_CONTACT, SeamlessCrossCheck.check(stand(14.5D, 64.0D), arm(END, EndPortalContact.END),
            standing(END, 14.5D, 64.0D)));
    }

    @Test
    void theEndPortalContactPointIsJustBelowThePlaneOrAtTheClaimedFeet() {
        assertEquals(64.0D, SeamlessCrossCheck.crossingPoint(stand(11.5D, 64.0D), arm(END, EndPortalContact.END), standing(END, 11.5D, 64.2D))
            .y(), 1.0E-9D);
        assertEquals(64.499D, SeamlessCrossCheck.crossingPoint(stand(11.5D, 64.7D), arm(END, EndPortalContact.END), standing(END, 11.5D, 64.8125D))
            .y(), 1.0E-9D);
    }

    @Test
    void standingOnTheBlockUnderAFloorFrameOpeningIsAcceptedFromJustPastThePlane() {
        TravelMessage.TravelCross claim = rest(1.5D, 64.0D);
        SeamlessCrossCheck.Server server = resting(1.5D, 64.0D, BACKING);

        assertEquals(SeamlessCrossCheck.Refusal.NONE, SeamlessCrossCheck.check(claim, arm(FLOOR_OPENING), server));
        Vec3d point = SeamlessCrossCheck.crossingPoint(claim, arm(FLOOR_OPENING), server);
        assertEquals(64.5D - 1.0E-3D, point.y() + EYE, 1.0E-9D);
        assertEquals(1.5D, point.x(), 0.0D);
    }

    @Test
    void aFloorFrameOpeningOverAirStillNeedsTheEyeToCrossItsPlane() {
        TravelMessage.TravelCross claim = rest(1.5D, 64.0D);
        SeamlessCrossCheck.Server server = resting(1.5D, 64.0D, OPEN);

        assertEquals(SeamlessCrossCheck.Refusal.NO_CROSSING, SeamlessCrossCheck.check(claim, arm(FLOOR_OPENING), server));
        assertEquals(64.0D, SeamlessCrossCheck.crossingPoint(claim, arm(FLOOR_OPENING), server).y(), 0.0D);
    }

    @Test
    void aRestingClaimBesideTheFloorFrameOpeningIsRefused() {
        assertEquals(SeamlessCrossCheck.Refusal.NO_CROSSING, SeamlessCrossCheck.check(rest(4.5D, 64.0D), arm(FLOOR_OPENING),
            resting(4.5D, 64.0D, BACKING)));
    }

    @Test
    void aRestingClaimOnTopOfSolidSelectedCellsIsRefused() {
        assertEquals(SeamlessCrossCheck.Refusal.NO_CROSSING, SeamlessCrossCheck.check(rest(1.5D, 65.0D), arm(FLOOR_OPENING),
            resting(1.5D, 65.0D, box -> box.getYa() < 65.0D)));
    }

    @Test
    void anEyeCrossingThroughAFloorFrameOpeningKeepsItsClaimedFeet() {
        TravelMessage.TravelCross fall = new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L, new TravelMessage.TravelPose(1.5D, 62.8D, 1.5D, 0.0F, 90.0F),
            new Vec3d(1.5D, 64.7D, 1.5D), new Vec3d(1.5D, 62.8D + EYE, 1.5D));
        SeamlessCrossCheck.Server server = resting(1.5D, 62.8D, box -> box.getYa() < 62.8D);

        assertEquals(SeamlessCrossCheck.Refusal.NONE, SeamlessCrossCheck.check(fall, arm(FLOOR_OPENING), server));
        assertEquals(62.8D, SeamlessCrossCheck.crossingPoint(fall, arm(FLOOR_OPENING), server).y(), 0.0D);
    }

    private static TravelMessage.TravelCross rest(double x, double feet) {
        return new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L, new TravelMessage.TravelPose(x, feet, 1.5D, 0.0F, 40.0F),
            new Vec3d(x, feet + EYE, 1.5D), new Vec3d(x, feet + EYE, 1.5D));
    }

    private static SeamlessCrossCheck.Server resting(double x, double feet, Predicate<Box> obstructed) {
        return new SeamlessCrossCheck.Server("minecraft:the_nether", FLOOR_OPENING, new TravelMessage.TravelPose(x, feet, 1.5D, 0.0F, 40.0F),
            new Vec3d(0.0D, 0.0D, 0.0D), EYE, bounds(x, feet, 1.5D), GravityFrame.WORLD, false, false, obstructed);
    }

    @Test
    void theEndExitStillNeedsTheEyeToCrossItsPlane() {
        assertEquals(SeamlessCrossCheck.Refusal.NO_CROSSING, SeamlessCrossCheck.check(stand(11.5D, 64.0D), arm(END, "minecraft:overworld"),
            standing(END, 11.5D, 64.0D)));
    }

    private static TravelMessage.TravelCross stand(double x, double feet) {
        return new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L, new TravelMessage.TravelPose(x, feet, 11.5D, 0.0F, 40.0F),
            new Vec3d(x, feet + EYE, 11.6D), new Vec3d(x, feet + EYE, 11.5D));
    }

    private static SeamlessCrossCheck.Server standing(ApertureDescriptor geometry, double x, double feet) {
        return new SeamlessCrossCheck.Server("minecraft:overworld", geometry, new TravelMessage.TravelPose(x, feet, 11.5D, 0.0F, 40.0F),
            new Vec3d(0.0D, 0.0D, 0.0D), EYE, bounds(x, feet, 11.5D), GravityFrame.WORLD, false, false, OPEN);
    }

    private static Box bounds(double x, double feet, double z) {
        return new Box(x - 0.3D, x + 0.3D, feet, feet + 1.8D, z - 0.3D, z + 0.3D);
    }

    private static TravelMessage.TravelCross walk(double x) {
        return new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L, new TravelMessage.TravelPose(x, 64.0D, 0.4D, 0.0F, 0.0F),
            new Vec3d(x, 64.0D + EYE, 0.6D), new Vec3d(x, 64.0D + EYE, 0.4D));
    }

    private static SeamlessCrossCheck.Server server(ApertureDescriptor geometry, double z, double speed) {
        return new SeamlessCrossCheck.Server("minecraft:the_nether", geometry, new TravelMessage.TravelPose(0.5D, 64.0D, z, 0.0F, 0.0F),
            new Vec3d(0.0D, 0.0D, -speed), EYE, bounds(0.5D, 64.0D, z), GravityFrame.WORLD, false, false, OPEN);
    }

    private static TravelMessage.TravelBegin arm(ApertureDescriptor geometry) {
        TravelMessage.TravelBegin sample = ClientViewFixtures.travelBegin();
        return new TravelMessage.TravelBegin(sample.token(), sample.generation(), sample.sourcePortal(), sample.sourceWorld(), geometry,
            sample.destinationToSource(), 1.0F, sample.world(), sample.arrival(), sample.environment(), TravelMessage.ArrivalRules.FRAME, true, 2, sample.doorCollision(),
            sample.returnTraversable());
    }

    private static TravelMessage.TravelBegin arm(ApertureDescriptor geometry, String destination) {
        TravelMessage.TravelBegin sample = ClientViewFixtures.travelBegin();
        TravelMessage.TravelWorld world = sample.world();
        EnvironmentState base = sample.environment();
        EnvironmentState.World sky = base.world();
        EnvironmentState environment = new EnvironmentState(base.gameTime(), base.sky(), base.fog(), base.lighting(), base.clouds(),
            base.transform(), base.dimension(), new EnvironmentState.World(destination, sky.clockTime(), sky.biomeKey(), sky.seaLevel(),
            sky.blockLight(), sky.skyLight(), sky.logicalHeight(), sky.hasCeiling(), sky.ambientLight(), sky.eyeMedium(), sky.hasFixedTime()),
            base.scale());
        return new TravelMessage.TravelBegin(sample.token(), sample.generation(), sample.sourcePortal(), "minecraft:overworld", geometry,
            sample.destinationToSource(), 1.0F, new TravelMessage.TravelWorld(destination, destination, world.seed(), world.debug(), world.flat(),
            world.seaLevel(), world.minY(), world.height()), sample.arrival(), environment, TravelMessage.ArrivalRules.FRAME, true, 2,
            sample.doorCollision(), false);
    }
}
