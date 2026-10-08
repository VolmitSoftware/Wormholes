package art.arcane.wormholes.modded.client;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientTravelCrossingTest {
    @Test
    public void interpolatedCameraSegmentCrossesOnlyTheAuthoritativeOpenAperture() {
        assertTrue(ClientSeamlessTravel.crossed(ClientTravelTestFixtures.geometry(),
            new Vec3(0.5, 1.62, 0.3), new Vec3(0.5, 1.62, 0.6)));
        assertFalse(ClientSeamlessTravel.crossed(ClientTravelTestFixtures.geometry(),
            new Vec3(0.5, 1.62, 0.3), new Vec3(0.5, 1.62, 0.4)));
        assertFalse(ClientSeamlessTravel.crossed(ClientTravelTestFixtures.geometry(),
            new Vec3(2.5, 1.62, 0.3), new Vec3(2.5, 1.62, 0.6)));
        assertFalse(ClientSeamlessTravel.crossed(ClientTravelTestFixtures.geometry(true, 59),
            new Vec3(0.5, 1.62, 0.3), new Vec3(0.5, 1.62, 0.6)));
    }

    @Test
    public void reverseCrossingUsesItsAuthorizedSideAndDoesNotCrossTwice() {
        assertTrue(ClientSeamlessTravel.crossed(ClientTravelTestFixtures.geometry(false, 63),
            new Vec3(0.5, 1.62, 0.7), new Vec3(0.5, 1.62, 0.4)));
        assertFalse(ClientSeamlessTravel.crossed(ClientTravelTestFixtures.geometry(),
            new Vec3(0.5, 1.62, 0.7), new Vec3(0.5, 1.62, 0.4)));
        assertFalse(ClientSeamlessTravel.crossed(ClientTravelTestFixtures.geometry(),
            new Vec3(0.5, 1.62, 0.5), new Vec3(0.5, 1.62, 0.7)));
    }

    @Test
    public void bothFacesSupportARoundTripAcrossEveryPortalOrientation() {
        Vec3d sourceOrigin = new Vec3d(0.5D, 0.5D, 0.5D);
        Vec3d destinationOrigin = new Vec3d(20.5D, 40.5D, 60.5D);
        for (Face sourceNormal : new Face[] {Face.N, Face.S, Face.E, Face.W, Face.U, Face.D}) {
            for (Face destinationNormal : new Face[] {Face.N, Face.S, Face.E, Face.W, Face.U, Face.D}) {
                for (boolean front : new boolean[] {true, false}) {
                    ApertureDescriptor source = geometry(sourceNormal, front, 0, 0, 0);
                    ApertureDescriptor destination = geometry(destinationNormal, !front, 20, 40, 60);
                    Vec3d normal = sourceNormal.toVector().multiply(front ? 1.0D : -1.0D);
                    Vec3d start = sourceOrigin.add(normal.multiply(0.2D));
                    Vec3d end = sourceOrigin.subtract(normal.multiply(0.2D));
                    Vec3d velocity = end.subtract(start);
                    assertTrue(ClientSeamlessTravel.crossed(source, vector(start), vector(end)));
                    PlaneCrossing outward = PlaneCrossing.create(source.frame(), sourceOrigin,
                        new PlaneCrossing.Motion(start, end, velocity, velocity));
                    Vec3d arrival = outward.outPoint(destination.frame(), destinationOrigin);
                    Vec3d returnEnd = outward.toward(destination.frame(), destinationOrigin).point(start);
                    Vec3d returnVelocity = outward.outVelocity(destination.frame()).multiply(-1.0D);
                    assertTrue(ClientSeamlessTravel.crossed(destination, vector(arrival), vector(returnEnd)));
                    PlaneCrossing returning = PlaneCrossing.create(destination.frame(), destinationOrigin,
                        new PlaneCrossing.Motion(arrival, returnEnd, returnVelocity, returnVelocity));
                    assertEquals(!front, returning.frontSide());
                    assertVector(start, returning.outPoint(source.frame(), sourceOrigin));
                    assertVector(velocity.multiply(-1.0D), returning.outVelocity(source.frame()));
                }
            }
        }
    }

    @Test
    public void enteringFromTheSideRequiresAPlaneCrossingInsideTheAperture() {
        ApertureDescriptor geometry = ClientTravelTestFixtures.geometry();
        assertFalse(ClientSeamlessTravel.crossed(geometry, new Vec3(2.1D, 1.62D, 0.7D), new Vec3(1.9D, 1.62D, 0.7D)));
        assertFalse(ClientSeamlessTravel.crossed(geometry, new Vec3(2.1D, 1.62D, 0.3D), new Vec3(1.9D, 1.62D, 0.7D)));
        assertTrue(ClientSeamlessTravel.crossed(geometry, new Vec3(2.05D, 1.62D, 0.3D), new Vec3(1.85D, 1.62D, 0.7D)));
    }

    private static ApertureDescriptor geometry(Face facing, boolean front, int x, int y, int z) {
        return new ApertureDescriptor(x, y, z, facing.ordinal(), front, 0, false, 2, 3, new long[] {63L}, ShapeDescriptor.FULL,
            0, 0, 1, 64, 0, 0, 0, 0, 0, 0, 0, 0.0D, 0, 11, List.of());
    }

    private static Vec3 vector(Vec3d point) {
        return new Vec3(point.x(), point.y(), point.z());
    }

    private static void assertVector(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x(), actual.x(), 1.0E-9D);
        assertEquals(expected.y(), actual.y(), 1.0E-9D);
        assertEquals(expected.z(), actual.z(), 1.0E-9D);
    }
}
