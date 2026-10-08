package art.arcane.wormholes.render;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.render.PortalSkinGeometry.SkinTransform;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PortalSkinGeometryTest {
    @Test
    void rectangularPanesReachEverySelectedCellEdgeOnEveryAxis() {
        for (Face face : Face.values()) {
            int normalAxis = face.axisIndex();
            List<Vec3d> cells = new ArrayList<>();
            int[] minimum = {-3, 64, -7};
            int[] maximum = {0, 66, -3};
            maximum[normalAxis] = minimum[normalAxis];
            for (int x = minimum[0]; x <= maximum[0]; x++) {
                for (int y = minimum[1]; y <= maximum[1]; y++) {
                    for (int z = minimum[2]; z <= maximum[2]; z++) {
                        cells.add(new Vec3d(x, y, z));
                    }
                }
            }
            ApertureCells aperture = new ApertureCells();
            aperture.setBlocks(cells);

            List<SkinTransform> panes = PortalSkinGeometry.panes(aperture, Frame.canonical(face), aperture.getApertureCenter());

            assertEquals(1, panes.size());
            SkinTransform pane = panes.getFirst();
            double[] anchors = {pane.anchorX(), pane.anchorY(), pane.anchorZ()};
            double[] translations = {pane.translationX(), pane.translationY(), pane.translationZ()};
            double[] scales = {pane.scaleX(), pane.scaleY(), pane.scaleZ()};
            for (int axis = 0; axis < 3; axis++) {
                double low = anchors[axis] + translations[axis];
                if (axis == normalAxis) {
                    assertEquals(minimum[axis] + 0.5D - 0.0625D, low, 1.0E-10D);
                    assertEquals(0.125D, scales[axis], 1.0E-10D);
                } else {
                    assertEquals(minimum[axis], low, 1.0E-10D);
                    assertEquals(maximum[axis] + 1.0D, low + scales[axis], 1.0E-10D);
                }
            }
        }
    }
}
