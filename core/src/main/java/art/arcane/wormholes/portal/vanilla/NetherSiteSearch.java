package art.arcane.wormholes.portal.vanilla;

import java.util.Objects;
import java.util.OptionalInt;

public final class NetherSiteSearch {
    private NetherSiteSearch() {
    }

    public static int maximumBaseY(int worldMaximum, int interiorHeight, boolean nether) {
        return Math.min(worldMaximum, nether ? 128 : worldMaximum) - Math.clamp(interiorHeight, 2, 21) - 3;
    }

    public static OptionalInt findBaseY(Options options, Blocks blocks) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(blocks, "blocks");
        int preferred = Math.clamp(options.preferredBaseY(), options.minimumBaseY(), options.maximumBaseY());
        int range = options.maximumBaseY() - options.minimumBaseY();
        for (boolean fullFootprint : new boolean[]{true, false}) {
            for (int distance = 0; distance <= range; distance++) {
                int below = preferred - distance;
                if (below >= options.minimumBaseY() && safe(options, blocks, below, fullFootprint)) {
                    return OptionalInt.of(below);
                }
                int above = preferred + distance;
                if (distance > 0 && above <= options.maximumBaseY() && safe(options, blocks, above, fullFootprint)) {
                    return OptionalInt.of(above);
                }
            }
        }
        return OptionalInt.empty();
    }

    private static boolean safe(Options options, Blocks blocks, int baseY, boolean fullFootprint) {
        if (blocks.cell(options.centerX(), baseY - 1, options.centerZ()) != Cell.FLOOR) {
            return false;
        }
        for (int dy = 0; dy <= options.height(); dy++) {
            if (blocks.cell(options.centerX(), baseY + dy, options.centerZ()) != Cell.CLEAR) {
                return false;
            }
        }
        if (!fullFootprint) {
            return true;
        }
        int width = NetherSitePlan.netherInteriorWidth(options.width());
        int padding = NetherSitePlan.netherPlatformPadding(width, options.height());
        int startX = options.centerX() - (options.alongX() ? width / 2 : 0);
        int startZ = options.centerZ() - (options.alongX() ? 0 : width / 2);
        for (int lateral = -1 - padding; lateral <= width + padding; lateral++) {
            for (int normal = -padding; normal <= padding; normal++) {
                int x = startX + (options.alongX() ? lateral : normal);
                int z = startZ + (options.alongX() ? normal : lateral);
                if (blocks.cell(x, baseY - 1, z) != Cell.FLOOR) {
                    return false;
                }
                int clearance = normal == 0 ? options.height() + 1 : 3;
                for (int dy = 0; dy < clearance; dy++) {
                    if (blocks.cell(x, baseY + dy, z) != Cell.CLEAR) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public record Options(int centerX, int centerZ, boolean alongX, int width, int height,
                          int preferredBaseY, int minimumBaseY, int maximumBaseY) {
        public Options {
            if (minimumBaseY > maximumBaseY || height < 2 || height > 21) {
                throw new IllegalArgumentException("Invalid Nether site height bounds");
            }
        }
    }

    @FunctionalInterface
    public interface Blocks {
        Cell cell(int x, int y, int z);
    }

    public enum Cell {
        FLOOR,
        CLEAR,
        BLOCKED
    }
}
