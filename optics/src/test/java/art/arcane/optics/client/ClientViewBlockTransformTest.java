package art.arcane.optics.client;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ClientViewBlockTransformTest {
    @Test
    void allSignedPermutationsRoundTripTheExactCapturedCellsAtHalfBlockTranslations() {
        int orientations = 0;
        for (Face xAxis : Face.values()) {
            for (Face yAxis : Face.values()) {
                for (Face zAxis : Face.values()) {
                    if (xAxis.getAxis() == yAxis.getAxis() || xAxis.getAxis() == zAxis.getAxis() || yAxis.getAxis() == zAxis.getAxis()) {
                        continue;
                    }
                    orientations++;
                    for (double fraction : new double[] {-0.5005D, -0.5D, -0.4995D, 0, 0.4995D, 0.5D, 0.5005D}) {
                        ProjectionEnvironment.Transform affine = new ProjectionEnvironment.Transform(xAxis, yAxis, zAxis,
                            new Vec3(32 + fraction, -48 - fraction, fraction));
                        ClientViewBlockTransform cells = new ClientViewBlockTransform(affine);
                        for (int coordinate : new int[] {-1000, -33, -17, -16, -1, 0, 15, 16, 1000}) {
                            int x = coordinate;
                            int y = coordinate - 7;
                            int z = coordinate + 19;
                            Vec3 sampled = affine.destinationPoint(x + 0.5D, y + 0.5D, z + 0.5D);
                            int nativeX = cells.destinationX(x, y, z);
                            int nativeY = cells.destinationY(x, y, z);
                            int nativeZ = cells.destinationZ(x, y, z);
                            String context = affine + " display=" + x + "," + y + "," + z;
                            assertEquals((int) Math.floor(sampled.x()), nativeX, context);
                            assertEquals((int) Math.floor(sampled.y()), nativeY, context);
                            assertEquals((int) Math.floor(sampled.z()), nativeZ, context);
                            assertEquals(x, cells.displayX(nativeX, nativeY, nativeZ), context);
                            assertEquals(y, cells.displayY(nativeX, nativeY, nativeZ), context);
                            assertEquals(z, cells.displayZ(nativeX, nativeY, nativeZ), context);
                        }
                    }
                }
            }
        }
        assertEquals(48, orientations);
    }
}
