package art.arcane.wormholes.network.view;

import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class ViewCaptureBounds {
    private ViewCaptureBounds() {
    }

    public static ViewBox compute(AxisAlignedBB area, Direction normal, Options options) {
        int depth = Math.max(0, options.depth());
        int lateral = Math.max(0, options.lateralPad()) + (int) Math.ceil(Math.max(0.0D, options.aperturePadding()));
        int expandX = normal.x() == 0 ? lateral : depth;
        int expandY = normal.y() == 0 ? lateral : depth;
        int expandZ = normal.z() == 0 ? lateral : depth;
        int minX = (int) Math.floor(Math.min(area.getXa(), area.getXb())) - expandX;
        int minY = (int) Math.floor(Math.min(area.getYa(), area.getYb())) - expandY;
        int minZ = (int) Math.floor(Math.min(area.getZa(), area.getZb())) - expandZ;
        int maxX = (int) Math.floor(Math.max(area.getXa(), area.getXb())) + expandX;
        int maxY = (int) Math.floor(Math.max(area.getYa(), area.getYb())) + expandY;
        int maxZ = (int) Math.floor(Math.max(area.getZa(), area.getZb())) + expandZ;
        return new ViewBox(minX, Math.max(minY, options.minHeight()), minZ,
            maxX, Math.min(maxY, options.maxHeight() - 1), maxZ);
    }

    public record Options(int depth, int lateralPad, double aperturePadding, int minHeight, int maxHeight) {
    }
}
