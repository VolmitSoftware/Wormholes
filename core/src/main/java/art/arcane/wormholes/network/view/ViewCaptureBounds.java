package art.arcane.wormholes.network.view;

import art.arcane.optics.math.BlockBox;

import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

public final class ViewCaptureBounds {
    private ViewCaptureBounds() {
    }

    public static BlockBox compute(Box area, Face normal, Options options) {
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
        return BlockBox.spanning(minX, Math.max(minY, options.minHeight()), minZ,
            maxX, Math.min(maxY, options.maxHeight() - 1), maxZ);
    }

    public static BlockBox computeMesh(Box area, int distance, int minHeight, int maxHeight) {
        int radius = Math.clamp(distance, 32, 512) + 32;
        return compute(area, Face.N, new Options(radius, radius, 0.0D, minHeight, maxHeight));
    }

    public record Options(int depth, int lateralPad, double aperturePadding, int minHeight, int maxHeight) {
    }
}
