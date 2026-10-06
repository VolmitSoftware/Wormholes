package art.arcane.optics.crossing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

final class PlaneCrossingTransformTest {
    @Test
    void frameTransformReproducesTheOutPointVelocityAndLookBitForBit() {
        Random random = new Random(0x70A4DL);
        for (Face sourceNormal : Face.values()) {
            for (Face sourceUp : Face.values()) {
                if (sourceUp.getAxis() == sourceNormal.getAxis()) {
                    continue;
                }
                Frame source = Frame.fromNormalUp(sourceNormal, sourceUp);
                for (Face exitNormal : Face.values()) {
                    Frame exit = Frame.canonical(exitNormal);
                    for (boolean front : new boolean[] {true, false}) {
                        for (int sample = 0; sample < 50; sample++) {
                            Vec3d origin = point(random);
                            PlaneCrossing crossing = new PlaneCrossing(source.view(front), origin, origin.add(point(random).multiply(0.0001D)),
                                point(random).multiply(0.0001D), point(random).normalize(), front);
                            Vec3d exitOrigin = point(random);
                            OpticTransform toward = CrossingFixtures.toward(crossing, exit, exitOrigin);
                            assertEquals(crossing.outPoint(exit, exitOrigin), toward.point(crossing.point()));
                            assertEquals(crossing.outVelocity(exit), toward.vector(crossing.velocity()));
                            assertEquals(crossing.outLook(exit), toward.vector(crossing.look()));
                        }
                    }
                }
            }
        }
    }

    private static Vec3d point(Random random) {
        return new Vec3d((random.nextDouble() * 2.0D - 1.0D) * 30_000_000.0D, random.nextDouble() * 320.0D - 64.0D,
            (random.nextDouble() * 2.0D - 1.0D) * 30_000_000.0D);
    }
}
