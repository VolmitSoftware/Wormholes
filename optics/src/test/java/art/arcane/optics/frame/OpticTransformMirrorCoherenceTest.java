package art.arcane.optics.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

final class OpticTransformMirrorCoherenceTest {
    private static final Vec3d ORIGIN = new Vec3d(10.5D, 64.5D, -3.5D);

    @Test
    void everyMirrorUsesTheCoherentQuarterTurnOfItsPlane() {
        for (Frame plane : FrameFixtures.all()) {
            for (QuarterTurn turns : QuarterTurn.values()) {
                OpticTransform raw = OpticTransform.mirror(plane, ORIGIN, turns);
                OpticTransform coherent = OpticTransform.mirror(plane, ORIGIN, turns.coherentFor(plane));
                assertEquals(coherent, raw, plane + " " + turns);
                assertEquals(AxisPermutation.mirror(plane, turns.coherentFor(plane)), AxisPermutation.mirror(plane, turns));
            }
        }
    }

    @Test
    void wallMirrorsCollapseQuarterTurnsWhileFloorAndCeilingMirrorsKeepThem() {
        for (Frame plane : FrameFixtures.all()) {
            OpticTransform flat = OpticTransform.mirror(plane, ORIGIN, QuarterTurn.DEGREES_0);
            OpticTransform half = OpticTransform.mirror(plane, ORIGIN, QuarterTurn.DEGREES_180);
            OpticTransform quarter = OpticTransform.mirror(plane, ORIGIN, QuarterTurn.DEGREES_90);
            OpticTransform threeQuarter = OpticTransform.mirror(plane, ORIGIN, QuarterTurn.DEGREES_270);
            if (QuarterTurn.supportsQuarterTurns(plane)) {
                assertNotEquals(flat, quarter, plane.toString());
                assertNotEquals(half, threeQuarter, plane.toString());
            } else {
                assertEquals(flat, quarter, plane.toString());
                assertEquals(half, threeQuarter, plane.toString());
            }
        }
    }

    @Test
    void viewWindowsPermutationsAndApertureMirrorsShareTheNormalizedMirror() {
        for (Frame plane : FrameFixtures.all()) {
            for (QuarterTurn turns : QuarterTurn.values()) {
                OpticTransform expected = OpticTransform.mirror(plane, ORIGIN, turns.coherentFor(plane));
                assertEquals(expected, ViewWindow.mirror(ORIGIN, plane, turns, true, 16.0D).transform());
                AxisPermutation permutation = AxisPermutation.mirror(plane, QuarterTurn.of(turns.getQuarterTurns()));
                for (Face face : Face.values()) {
                    assertEquals(expected.face(face), permutation.face(face), plane + " " + turns + " " + face);
                }
            }
        }
        for (Face normal : Face.values()) {
            for (QuarterTurn turns : QuarterTurn.values()) {
                ApertureDescriptor descriptor = mirror(Frame.canonical(normal), turns);
                OpticTransform expected = OpticTransform.mirror(descriptor.frame(), descriptor.apertureArea().center(),
                    turns.coherentFor(descriptor.frame()));
                assertEquals(expected, descriptor.mirrorTransform(), normal + " " + turns);
            }
        }
    }

    private static ApertureDescriptor mirror(Frame frame, QuarterTurn turns) {
        ApertureCells aperture = new ApertureCells();
        Face normal = frame.getNormal();
        aperture.setArea(new Box(40, normal.x() != 0 ? 40.999D : 42.999D, 70, normal.y() != 0 ? 70.999D : 72.999D,
            20, normal.z() != 0 ? 20.999D : 22.999D));
        ApertureDescriptor.Source source = new ApertureDescriptor.Source(aperture, frame, true, true, turns.getQuarterTurns(), 2.0D,
            0.75D, 0.2D, 16, 1, ApertureDescriptor.BLACKOUT_OFF, 0, ApertureDescriptor.MASK_AIR_PROJECT,
            ProjectedBlockClaim.LightingPolicy.LOCAL, 0, ApertureDescriptor.KIND_FRAME, 0.0D, 0, 0L, List.of());
        return ApertureDescriptor.fromPortal(source).orElseThrow();
    }
}
