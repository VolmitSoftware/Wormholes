package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.PortalAperture;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class Frustum4DCullingTest {
    private double aperturePadding = 0.75D;
    private double nearPadding = 2.0D;
    private double cullingRatio = 0.2D;

    @Test
    public void axialViewBuildsSingleFaceCone() {
        double previousRatio = cullingRatio;
        cullingRatio = 0.2D;
        try {
            Frustum4D frustum = new Frustum4D(new GeometryVector( 1.5D, 1.5D, 0.0D), new TestStructure(), new Frustum4D.Options(16.0D, 16.0D, nearPadding, cullingRatio, aperturePadding));

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
            Frustum4D frustum = new Frustum4D(new GeometryVector( 8.0D, 1.5D, 0.0D), new TestStructure(), new Frustum4D.Options(16.0D, 16.0D, nearPadding, cullingRatio, aperturePadding));

            assertEquals(2, frustum.getFaceCount());
        } finally {
            cullingRatio = previousRatio;
        }
    }

    private static final class TestStructure implements PortalAperture {
        @Override
        public AxisAlignedBB getArea() {
            return new AxisAlignedBB(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D);
        }

        @Override
        public GeometryVector getApertureCenter() {
            return new GeometryVector( 1.5D, 1.5D, 5.0D);
        }

        @Override
        public List<AxisAlignedBB> getCachedApertureFaces(Direction face) {
            List<AxisAlignedBB> faces = new ArrayList<AxisAlignedBB>();
            switch (face) {
                case E:
                    faces.add(new AxisAlignedBB(3.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D));
                    break;
                case W:
                    faces.add(new AxisAlignedBB(0.0D, 0.0D, 0.0D, 3.0D, 5.0D, 5.0D));
                    break;
                case U:
                    faces.add(new AxisAlignedBB(0.0D, 3.0D, 3.0D, 3.0D, 5.0D, 5.0D));
                    break;
                case D:
                    faces.add(new AxisAlignedBB(0.0D, 3.0D, 0.0D, 0.0D, 5.0D, 5.0D));
                    break;
                case S:
                    faces.add(new AxisAlignedBB(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D));
                    break;
                case N:
                    faces.add(new AxisAlignedBB(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D));
                    break;
                default:
                    break;
            }
            return faces;
        }
    }
}
