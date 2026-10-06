package art.arcane.wormholes.render;

import art.arcane.optics.math.Vec3;
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
    public static List<SkinTransform> panes(ApertureCells structure, Frame frame, Vec3 origin) {
        Axis normalAxis = frame.getNormal().getAxis();
        double planeCoordinate = axisComponent(origin, normalAxis);
        List<Vec3> cells = structure.getBlockPositions();
        if (structure.isFullCuboid() || cells.isEmpty() || cells.size() > MAX_PER_CELL_PANES) {
            return List.of(skinTransforms(structure.getArea(), normalAxis, planeCoordinate, SURFACE_THICKNESS_BLOCKS));
        }
        List<SkinTransform> panes = new ArrayList<SkinTransform>(cells.size());
        for (Vec3 cell : cells) {
            int x = cell.getBlockX();
            int y = cell.getBlockY();
            int z = cell.getBlockZ();
            Box cellBox = new Box(x, x + 1.0D, y, y + 1.0D, z, z + 1.0D);
            panes.add(skinTransforms(cellBox, normalAxis, planeCoordinate, SURFACE_THICKNESS_BLOCKS));
        }
        return panes;
    }

    private static double axisComponent(Vec3 vector, Axis axis) {
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
