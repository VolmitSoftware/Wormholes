package art.arcane.optics.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;

import org.junit.jupiter.api.Test;

import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.math.Face;

public final class ProjectorTransformBoxTest {
    @Test
    public void signedTransformsMatchEightCornerBoundsForEveryFrameAndMirrorRotation() {
        Random random = new Random(8675309L);
        for (Face normal : Face.values()) {
            for (Face up : Face.values()) {
                if (up.getAxis() == normal.getAxis()) {
                    continue;
                }
                Frame from = Frame.fromNormalUp(normal, up);
                for (Face targetNormal : Face.values()) {
                    Frame to = Frame.canonical(targetNormal);
                    ProjectorFrameTransform transform = new ProjectorFrameTransform();
                    transform.configure(from, to, -29_999_999.5D, -63.25D, 29_999_999.75D,
                        29_999_999.25D, 125.5D, -29_999_999.75D);
                    assertBoxes(transform, random);
                }
                for (int rotation = -5; rotation <= 5; rotation++) {
                    ProjectorFrameTransform transform = new ProjectorFrameTransform();
                    transform.configureMirror(from, rotation, -29_999_999.5D, -63.25D, 29_999_999.75D);
                    assertBoxes(transform, random);
                }
            }
        }
    }

    @Test
    public void integerEndpointWrappingAndEmptyBoxesKeepTheirExistingSemantics() {
        ProjectorFrameTransform transform = new ProjectorFrameTransform();
        Frame frame = Frame.canonical(Face.N);
        transform.configure(frame, frame, 0, 0, 0, 0, 0, 0);
        assertEquals(PlateBox.EMPTY, transform.transformBox(PlateBox.EMPTY, 4));
        PlateBox wrapped = new PlateBox(Integer.MAX_VALUE - 2, -3, -4, 5, 2, 3);
        assertThrows(IllegalArgumentException.class, () -> reference(transform, wrapped, 1));
        assertThrows(IllegalArgumentException.class, () -> transform.transformBox(wrapped, 1));
    }

    private static void assertBoxes(ProjectorFrameTransform transform, Random random) {
        for (int sample = 0; sample < 20; sample++) {
            PlateBox box = new PlateBox(random.nextInt(60_000_000) - 30_000_000,
                random.nextInt(768) - 384, random.nextInt(60_000_000) - 30_000_000,
                1 + random.nextInt(64), 1 + random.nextInt(64), 1 + random.nextInt(64));
            int margin = random.nextInt(4);
            assertEquals(reference(transform, box, margin), transform.transformBox(box, margin));
        }
    }

    private static PlateBox reference(ProjectorFrameTransform transform, PlateBox box, int margin) {
        double[] transformed = new double[3];
        double[] minimum = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] maximum = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int corner = 0; corner < 8; corner++) {
            double x = ((corner & 1) == 0 ? box.minX() : box.minX() + box.sizeX() - 1) + 0.5D;
            double y = ((corner & 2) == 0 ? box.minY() : box.minY() + box.sizeY() - 1) + 0.5D;
            double z = ((corner & 4) == 0 ? box.minZ() : box.minZ() + box.sizeZ() - 1) + 0.5D;
            transform.apply(x, y, z, transformed);
            for (int axis = 0; axis < 3; axis++) {
                minimum[axis] = Math.min(minimum[axis], transformed[axis]);
                maximum[axis] = Math.max(maximum[axis], transformed[axis]);
            }
        }
        return PlateBox.spanning((int) Math.floor(minimum[0]) - margin,
            (int) Math.floor(minimum[1]) - margin, (int) Math.floor(minimum[2]) - margin,
            (int) Math.floor(maximum[0]) + margin, (int) Math.floor(maximum[1]) + margin,
            (int) Math.floor(maximum[2]) + margin);
    }
}
