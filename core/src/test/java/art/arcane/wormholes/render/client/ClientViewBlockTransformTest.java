package art.arcane.wormholes.render.client;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ClientViewBlockTransformTest {
    @Test
    void allSignedPermutationsRoundTripTheExactCapturedCellsAtHalfBlockTranslations() {
        int orientations = 0;
        for (Direction xAxis : Direction.values()) {
            for (Direction yAxis : Direction.values()) {
                for (Direction zAxis : Direction.values()) {
                    if (xAxis.getAxis() == yAxis.getAxis() || xAxis.getAxis() == zAxis.getAxis() || yAxis.getAxis() == zAxis.getAxis()) {
                        continue;
                    }
                    orientations++;
                    for (double fraction : new double[] {-0.5005D, -0.5D, -0.4995D, 0, 0.4995D, 0.5D, 0.5005D}) {
                        ClientViewEnvironment.Transform affine = new ClientViewEnvironment.Transform(xAxis, yAxis, zAxis,
                            new GeometryVector(32 + fraction, -48 - fraction, fraction));
                        ClientViewBlockTransform cells = new ClientViewBlockTransform(affine);
                        for (int coordinate : new int[] {-1000, -33, -17, -16, -1, 0, 15, 16, 1000}) {
                            int x = coordinate;
                            int y = coordinate - 7;
                            int z = coordinate + 19;
                            GeometryVector sampled = affine.destinationPoint(x + 0.5D, y + 0.5D, z + 0.5D);
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
