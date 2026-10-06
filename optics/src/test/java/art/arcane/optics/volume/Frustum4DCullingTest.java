package art.arcane.optics.volume;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import art.arcane.optics.aperture.Aperture;
import art.arcane.optics.math.Vec3;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

public final class Frustum4DCullingTest {
    private double aperturePadding = 0.75D;
    private double nearPadding = 2.0D;
    private double cullingRatio = 0.2D;

    @Test
    public void axialViewBuildsSingleFaceCone() {
        double previousRatio = cullingRatio;
        cullingRatio = 0.2D;
        try {
            ViewVolume frustum = new ViewVolume(new Vec3( 1.5D, 1.5D, 0.0D), new TestStructure(), new ViewVolume.Options(16.0D, 16.0D, nearPadding, cullingRatio, aperturePadding));

            assertEquals(1, frustum.getFaceCount());
        } finally {
            cullingRatio = previousRatio;
        }
    }

    @Test
    public void diagonalViewBuildsOnlyFacesTowardObserver() {
        double previousRatio = cullingRatio;
        cullingRatio = 0.2D;
        try {
            ViewVolume frustum = new ViewVolume(new Vec3( 8.0D, 1.5D, 0.0D), new TestStructure(), new ViewVolume.Options(16.0D, 16.0D, nearPadding, cullingRatio, aperturePadding));

            assertEquals(2, frustum.getFaceCount());
        } finally {
            cullingRatio = previousRatio;
        }
    }

    private static final class TestStructure implements Aperture {
        @Override
        public Box getArea() {
            return new Box(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D);
        }

        @Override
        public Vec3 getApertureCenter() {
            return new Vec3( 1.5D, 1.5D, 5.0D);
        }

        @Override
        public List<Box> getCachedApertureFaces(Face face) {
            List<Box> faces = new ArrayList<Box>();
            switch (face) {
                case E:
                    faces.add(new Box(3.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D));
                    break;
                case W:
                    faces.add(new Box(0.0D, 0.0D, 0.0D, 3.0D, 5.0D, 5.0D));
                    break;
                case U:
                    faces.add(new Box(0.0D, 3.0D, 3.0D, 3.0D, 5.0D, 5.0D));
                    break;
                case D:
                    faces.add(new Box(0.0D, 3.0D, 0.0D, 0.0D, 5.0D, 5.0D));
                    break;
                case S:
                    faces.add(new Box(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D));
                    break;
                case N:
                    faces.add(new Box(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D));
                    break;
                default:
                    break;
            }
            return faces;
        }
    }
}
