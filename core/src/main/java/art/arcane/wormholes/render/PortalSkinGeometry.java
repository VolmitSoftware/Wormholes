package art.arcane.wormholes.render;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Axis;
import art.arcane.optics.math.Box;
import java.util.ArrayList;
import java.util.List;

public final class PortalSkinGeometry {
    private static final int MAX_PER_CELL_PANES = 128;
    // Skin panes are a thin face centered on the portal plane, as deep as a glass pane.
    private static final double SURFACE_THICKNESS_BLOCKS = 0.125D;
    private PortalSkinGeometry() { }
    public static List<SkinTransform> panes(ApertureCells structure, Frame frame, Vec3d origin) {
        Axis normalAxis = frame.getNormal().getAxis();
        double planeCoordinate = axisComponent(origin, normalAxis);
        List<Vec3d> cells = structure.getBlockPositions();
        if (cells.isEmpty()) {
            return List.of(skinTransforms(structure.getArea(), normalAxis, planeCoordinate, SURFACE_THICKNESS_BLOCKS));
        }
        if (structure.isFullCuboid() || cells.size() > MAX_PER_CELL_PANES) {
            Box area = structure.getArea();
            Box cellBounds = new Box(Math.floor(area.getXa()), Math.floor(area.getXb()) + 1.0D,
                Math.floor(area.getYa()), Math.floor(area.getYb()) + 1.0D,
                Math.floor(area.getZa()), Math.floor(area.getZb()) + 1.0D);
            return List.of(skinTransforms(cellBounds, normalAxis, planeCoordinate, SURFACE_THICKNESS_BLOCKS));
        }
        List<SkinTransform> panes = new ArrayList<SkinTransform>(cells.size());
        for (Vec3d cell : cells) {
            int x = cell.blockX();
            int y = cell.blockY();
            int z = cell.blockZ();
            Box cellBox = new Box(x, x + 1.0D, y, y + 1.0D, z, z + 1.0D);
            panes.add(skinTransforms(cellBox, normalAxis, planeCoordinate, SURFACE_THICKNESS_BLOCKS));
        }
        return panes;
    }

    private static double axisComponent(Vec3d vector, Axis axis) {
        return switch (axis) { case X -> vector.x(); case Y -> vector.y(); case Z -> vector.z(); };
    }
    public static SkinTransform skinTransforms(Box area, Axis normalAxis, double planeCoordinate, double thickness) {
        double sizeX = normalAxis == Axis.X ? thickness : area.sizeX();
        double sizeY = normalAxis == Axis.Y ? thickness : area.sizeY();
        double sizeZ = normalAxis == Axis.Z ? thickness : area.sizeZ();
        double anchorX = normalAxis == Axis.X ? planeCoordinate : area.getXa();
        double anchorY = normalAxis == Axis.Y ? planeCoordinate : area.getYa();
        double anchorZ = normalAxis == Axis.Z ? planeCoordinate : area.getZa();
        double translationX = normalAxis == Axis.X ? -thickness / 2.0D : 0.0D;
        double translationY = normalAxis == Axis.Y ? -thickness / 2.0D : 0.0D;
        double translationZ = normalAxis == Axis.Z ? -thickness / 2.0D : 0.0D;
        return new SkinTransform(anchorX, anchorY, anchorZ, translationX, translationY, translationZ, sizeX, sizeY, sizeZ);
    }

    public record SkinTransform(
        double anchorX,
        double anchorY,
        double anchorZ,
        double translationX,
        double translationY,
        double translationZ,
        double scaleX,
        double scaleY,
        double scaleZ) {
    }

}
