package art.arcane.optics.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class OpticTransformCellAlignedTest {
    @Test
    void allSignedPermutationsRoundTripTheExactCapturedCellsAtHalfBlockTranslations() {
        int[] destination = new int[3];
        int[] display = new int[3];
        double[] sampled = new double[3];
        for (int index = 0; index < 48; index++) {
            AxisPermutation permutation = AxisPermutation.ofIndex(index);
            for (double fraction : new double[] {-0.5005D, -0.5D, -0.4995D, 0, 0.4995D, 0.5D, 0.5005D}) {
                OpticTransform affine = OpticTransform.of(permutation, 32 + fraction, -48 - fraction, fraction);
                OpticTransform cells = affine.cellAligned();
                for (int coordinate : new int[] {-1000, -33, -17, -16, -1, 0, 15, 16, 1000}) {
                    int x = coordinate;
                    int y = coordinate - 7;
                    int z = coordinate + 19;
                    affine.inverse().pointInto(x + 0.5D, y + 0.5D, z + 0.5D, sampled);
                    cells.inverse().cellInto(x, y, z, destination);
                    String context = affine + " display=" + x + "," + y + "," + z;
                    assertEquals((int) Math.floor(sampled[0]), destination[0], context);
                    assertEquals((int) Math.floor(sampled[1]), destination[1], context);
                    assertEquals((int) Math.floor(sampled[2]), destination[2], context);
                    cells.cellInto(destination[0], destination[1], destination[2], display);
                    assertEquals(x, display[0], context);
                    assertEquals(y, display[1], context);
                    assertEquals(z, display[2], context);
                }
            }
        }
    }
}
