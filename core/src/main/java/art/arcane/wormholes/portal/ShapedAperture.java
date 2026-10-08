package art.arcane.wormholes.portal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.CellAperture;
import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.PlaneShape;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.shape.ShapeRaster;

public final class ShapedAperture {
    private final ApertureDescriptor geometry;
    private final ApertureDescriptor outline;
    private final PlaneShape plane;
    private final List<Vec3d> cells;
    private final int normalAxis;

    private ShapedAperture(ApertureDescriptor geometry, ApertureDescriptor outline, PlaneShape plane, List<Vec3d> cells) {
        this.geometry = geometry;
        this.outline = outline;
        this.plane = plane;
        this.cells = cells;
        normalAxis = geometry.facingDirection().axisIndex();
    }

    public static ShapedAperture of(CellAperture built, Frame frame, ShapeDescriptor shape) {
        Objects.requireNonNull(built, "built");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(shape, "shape");
        ApertureDescriptor bounds = ApertureDescriptor.fromPortal(source(built, frame, shape)).orElse(null);
        if (bounds == null) {
            return null;
        }
        PlaneShape plane = bounds.planeShape();
        ShapeRaster raster = plane.raster(ShapeRaster.DEFAULT_SUBSAMPLES);
        List<Vec3d> cells = new ArrayList<Vec3d>(raster.insideCount());
        if (raster.cells(bounds.originX(), bounds.originY(), bounds.originZ(), bounds.facingDirection(), bounds.apertureMask(), cells) == 0) {
            return null;
        }
        ApertureCells effective = new ApertureCells();
        effective.restore(built.getArea(), cells);
        ApertureDescriptor geometry = ApertureDescriptor.fromPortal(source(effective, frame, shape)).orElse(null);
        return geometry == null ? null : new ShapedAperture(geometry, bounds, plane, List.copyOf(cells));
    }

    public ShapeDescriptor shape() {
        return geometry.shape();
    }

    public ApertureDescriptor outline() {
        return outline;
    }

    public List<Vec3d> cells() {
        return cells;
    }

    public boolean contains(double x, double y, double z) {
        if (!geometry.containsCell((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))) {
            return false;
        }
        double column = normalAxis == 0 ? z - geometry.originZ() : x - geometry.originX();
        double row = normalAxis == 1 ? z - geometry.originZ() : y - geometry.originY();
        return plane.contains(column, row);
    }

    public boolean admits(double x, double y, double z, double eyeHeight) {
        return contains(x, normalAxis == 1 ? y : y + eyeHeight, z);
    }

    private static ApertureDescriptor.Source source(CellAperture aperture, Frame frame, ShapeDescriptor shape) {
        return new ApertureDescriptor.Source(aperture, frame, true, false, 0, 0.0D, 0.0D, 1.0D, 0, 0, ApertureDescriptor.BLACKOUT_OFF, 0,
            ApertureDescriptor.MASK_AIR_PROJECT, BlockClaim.LightingPolicy.LOCAL, 0, ApertureKind.FRAME, 0.0D, 0, 0L, shape, List.of());
    }
}
