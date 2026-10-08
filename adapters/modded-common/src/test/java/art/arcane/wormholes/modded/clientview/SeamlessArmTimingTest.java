package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.network.client.TravelMessage;
import org.junit.Test;

import java.util.UUID;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SeamlessArmTimingTest {
    private static final UUID FLOOR = new UUID(0, 1);
    private static final UUID CEILING = new UUID(0, 2);

    @Test
    public void aServerDetectedCrossingWaitsAFewTicksForTheClientClaim() {
        MinecraftSeamlessTravel.ClaimGrace grace = new MinecraftSeamlessTravel.ClaimGrace();
        assertTrue(grace.waiting(FLOOR, 10L));
        assertTrue(grace.waiting(FLOOR, 12L));
        assertFalse(grace.waiting(FLOOR, 13L));
    }

    @Test
    public void everyCrossingThroughTheSamePortalGetsItsOwnGraceOnceTheLastOneSettled() {
        MinecraftSeamlessTravel.ClaimGrace grace = new MinecraftSeamlessTravel.ClaimGrace();
        assertTrue(grace.waiting(FLOOR, 10L));
        grace.settled();
        assertTrue(grace.waiting(FLOOR, 16L));
        assertTrue(grace.waiting(FLOOR, 18L));
        assertFalse(grace.waiting(FLOOR, 19L));
        assertTrue(grace.waiting(FLOOR, 22L));
    }

    @Test
    public void armsKeepTheirSideWhileAFastTravelerIsStillWithinAFewTicksOfThePlane() {
        assertTrue(MinecraftSeamlessTravel.keepsSide(1.5D, 0.0D));
        assertFalse(MinecraftSeamlessTravel.keepsSide(2.5D, 0.0D));
        assertTrue(MinecraftSeamlessTravel.keepsSide(-9.0D, 3.9D));
        assertFalse(MinecraftSeamlessTravel.keepsSide(-10.5D, 3.9D));
    }

    @Test
    public void anArmKeepsItsRememberedSideWhileTheTravelerMovesAlongThePlane() {
        MinecraftSeamlessTravel.SideMemory sides = new MinecraftSeamlessTravel.SideMemory();
        assertFalse(sides.front(true, false, 0.4D, 0.0D, true));
        assertTrue(sides.front(true, true, -0.4D, 0.0D, true));
        assertTrue(sides.front(false, false, 0.4D, 0.0D, true));
        assertFalse(sides.front(true, true, -2.5D, 0.0D, true));
    }

    @Test
    public void walkingAroundTheApertureChangesTheArmedSideWithoutLeavingTheHysteresisBand() {
        MinecraftSeamlessTravel.SideMemory sides = new MinecraftSeamlessTravel.SideMemory();
        assertTrue(sides.front(true, false, 0.01D, 0.4D, false));
        assertFalse(sides.front(true, true, -0.01D, 0.4D, false));
        assertFalse(sides.front(true, false, -0.01D, 0.4D, true));
        assertTrue(sides.front(true, true, 0.01D, 0.4D, true));
    }

    @Test
    public void sideMemoryChecksTheProjectedEyeAgainstEveryApertureOrientation() {
        for (Face normal : new Face[] {Face.N, Face.S, Face.E, Face.W, Face.U, Face.D}) {
            ApertureDescriptor geometry = geometry(normal, 63L, ShapeDescriptor.FULL);
            Vec3d center = new Vec3d(0.5D, 0.5D, 0.5D);
            Vec3d normalVector = normal.toVector();
            Vec3d right = geometry.frame().getRight().toVector();
            assertTrue(MinecraftSeamlessTravel.projectsIntoAperture(geometry, center.add(normalVector.multiply(1.9D))));
            assertTrue(MinecraftSeamlessTravel.projectsIntoAperture(geometry, center.add(normalVector.multiply(-1.9D))));
            assertFalse(MinecraftSeamlessTravel.projectsIntoAperture(geometry, center.add(right.multiply(3.0D))));
        }
        assertFalse(MinecraftSeamlessTravel.projectsIntoAperture(geometry(Face.N, 62L, ShapeDescriptor.FULL), new Vec3d(0.5D, 0.5D, 0.7D)));
        ApertureDescriptor circle = geometry(Face.N, 63L, ShapeDescriptor.parse("circle"));
        assertTrue(MinecraftSeamlessTravel.projectsIntoAperture(circle, new Vec3d(1.0D, 1.5D, 0.7D)));
        assertFalse(MinecraftSeamlessTravel.projectsIntoAperture(circle, new Vec3d(0.01D, 0.01D, 0.7D)));
    }

    @Test
    public void aCrossingThatLandsBesideAPortalArmsItFromTheSideItLandedOn() {
        MinecraftSeamlessTravel.SideMemory sides = new MinecraftSeamlessTravel.SideMemory();
        sides.relocated();
        assertTrue(sides.front(true, false, 0.0003D, 0.0D, true));
        assertFalse(sides.front(true, true, -1.6D, 0.0D, true));
        sides.evaluated();
        assertTrue(sides.front(true, true, -1.6D, 0.0D, true));
    }

    @Test
    public void anArmIsResentWhenThePortalArrivalRulesOrTravelScaleChange() {
        TravelMessage.ArrivalRules ratio = withScale(ScaleRule.ratio(0.25D, 4.0D));
        TravelMessage.ArrivalRules motion = withScale(ScaleRule.motion());
        assertTrue(MinecraftSeamlessTravel.armedWith(ratio, 3.0F, withScale(ScaleRule.ratio(0.25D, 4.0D)), 3.0D));
        assertFalse(MinecraftSeamlessTravel.armedWith(ratio, 3.0F, motion, 3.0D));
        assertFalse(MinecraftSeamlessTravel.armedWith(motion, 1.0F, motion, 3.0D));
    }

    @Test
    public void aDifferentPortalStartsAFreshGrace() {
        MinecraftSeamlessTravel.ClaimGrace grace = new MinecraftSeamlessTravel.ClaimGrace();
        assertTrue(grace.waiting(FLOOR, 10L));
        assertTrue(grace.waiting(CEILING, 12L));
        assertTrue(grace.waiting(CEILING, 14L));
        assertFalse(grace.waiting(CEILING, 15L));
    }

    private static TravelMessage.ArrivalRules withScale(ScaleRule scale) {
        TravelMessage.ArrivalRules frame = TravelMessage.ArrivalRules.FRAME;
        return new TravelMessage.ArrivalRules(frame.orientation(), frame.gravityFlip(), frame.momentum(), scale);
    }

    private static ApertureDescriptor geometry(Face facing, long mask, ShapeDescriptor shape) {
        return new ApertureDescriptor(0, 0, 0, facing.ordinal(), true, 0, false, 2, 3, new long[] {mask}, shape,
            0, 0, 1, 64, 0, 0, 0, 0, 0, 0, 0, 0.0D, 0, 11, List.of());
    }
}
