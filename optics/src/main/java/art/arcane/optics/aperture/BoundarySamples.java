package art.arcane.optics.aperture;

import java.util.List;

import art.arcane.optics.math.CellKeys;
import art.arcane.optics.math.Axis;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

public final class BoundarySamples {
    private BoundarySamples() {
    }

    public static <R> void append(List<R> outline, LongOpenHashSet occupied, int x, int y, int z, Axis normalAxis, int samplesPerEdge, PointFactory<R> factory) {
        switch (normalAxis) {
            case X -> {
                if (!occupied.contains(CellKeys.pack(x, y - 1, z))) {
                    addLine(outline, samplesPerEdge, factory, x + 0.5D, y, z, x + 0.5D, y, z + 1.0D);
                }
                if (!occupied.contains(CellKeys.pack(x, y + 1, z))) {
                    addLine(outline, samplesPerEdge, factory, x + 0.5D, y + 1.0D, z, x + 0.5D, y + 1.0D, z + 1.0D);
                }
                if (!occupied.contains(CellKeys.pack(x, y, z - 1))) {
                    addLine(outline, samplesPerEdge, factory, x + 0.5D, y, z, x + 0.5D, y + 1.0D, z);
                }
                if (!occupied.contains(CellKeys.pack(x, y, z + 1))) {
                    addLine(outline, samplesPerEdge, factory, x + 0.5D, y, z + 1.0D, x + 0.5D, y + 1.0D, z + 1.0D);
                }
            }
            case Y -> {
                if (!occupied.contains(CellKeys.pack(x - 1, y, z))) {
                    addLine(outline, samplesPerEdge, factory, x, y + 0.5D, z, x, y + 0.5D, z + 1.0D);
                }
                if (!occupied.contains(CellKeys.pack(x + 1, y, z))) {
                    addLine(outline, samplesPerEdge, factory, x + 1.0D, y + 0.5D, z, x + 1.0D, y + 0.5D, z + 1.0D);
                }
                if (!occupied.contains(CellKeys.pack(x, y, z - 1))) {
                    addLine(outline, samplesPerEdge, factory, x, y + 0.5D, z, x + 1.0D, y + 0.5D, z);
                }
                if (!occupied.contains(CellKeys.pack(x, y, z + 1))) {
                    addLine(outline, samplesPerEdge, factory, x, y + 0.5D, z + 1.0D, x + 1.0D, y + 0.5D, z + 1.0D);
                }
            }
            case Z -> {
                if (!occupied.contains(CellKeys.pack(x - 1, y, z))) {
                    addLine(outline, samplesPerEdge, factory, x, y, z + 0.5D, x, y + 1.0D, z + 0.5D);
                }
                if (!occupied.contains(CellKeys.pack(x + 1, y, z))) {
                    addLine(outline, samplesPerEdge, factory, x + 1.0D, y, z + 0.5D, x + 1.0D, y + 1.0D, z + 0.5D);
                }
                if (!occupied.contains(CellKeys.pack(x, y - 1, z))) {
                    addLine(outline, samplesPerEdge, factory, x, y, z + 0.5D, x + 1.0D, y, z + 0.5D);
                }
                if (!occupied.contains(CellKeys.pack(x, y + 1, z))) {
                    addLine(outline, samplesPerEdge, factory, x, y + 1.0D, z + 0.5D, x + 1.0D, y + 1.0D, z + 0.5D);
                }
            }
        }
    }

    private static <R> void addLine(List<R> points, int samplesPerEdge, PointFactory<R> factory, double x0, double y0, double z0, double x1, double y1, double z1) {
        for (int sample = 0; sample < samplesPerEdge; sample++) {
            double t = (sample + 0.5D) / samplesPerEdge;
            points.add(factory.create(x0 + ((x1 - x0) * t), y0 + ((y1 - y0) * t), z0 + ((z1 - z0) * t)));
        }
    }

    public interface PointFactory<R> {
        R create(double x, double y, double z);
    }
}
