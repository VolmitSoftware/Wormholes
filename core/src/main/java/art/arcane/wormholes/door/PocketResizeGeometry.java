package art.arcane.wormholes.door;

public final class PocketResizeGeometry {
    private PocketResizeGeometry() {
    }

    /**
     * Blocks the reshape destroys: everything left outside the new room, plus the
     * interior blocks the new walls are laid through.
     */
    public static void forEachDisplacedBlock(
        PocketLayout previous,
        PocketLayout updated,
        PocketLayout.BlockVisitor visitor
    ) {
        if (updated.size() >= previous.size()) {
            return;
        }
        visitBox(
            updated.maxX() + 1, previous.maxX(),
            previous.minY(), previous.maxY(),
            previous.minZ(), previous.maxZ(),
            visitor
        );
        visitBox(
            previous.minX(), updated.maxX(),
            updated.maxY() + 1, previous.maxY(),
            previous.minZ(), previous.maxZ(),
            visitor
        );
        visitBox(
            previous.minX(), updated.maxX(),
            previous.minY(), updated.maxY(),
            updated.maxZ() + 1, previous.maxZ(),
            visitor
        );
        visitBox(
            updated.maxX(), updated.maxX(),
            updated.minY() + 1, updated.maxY(),
            updated.minZ() + 1, updated.maxZ(),
            visitor
        );
        visitBox(
            updated.minX() + 1, updated.maxX() - 1,
            updated.maxY(), updated.maxY(),
            updated.minZ() + 1, updated.maxZ(),
            visitor
        );
        visitBox(
            updated.minX() + 1, updated.maxX() - 1,
            updated.minY() + 1, updated.maxY() - 1,
            updated.maxZ(), updated.maxZ(),
            visitor
        );
    }

    private static void visitBox(
        int minX,
        int maxX,
        int minY,
        int maxY,
        int minZ,
        int maxZ,
        PocketLayout.BlockVisitor visitor
    ) {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    visitor.visit(x, y, z);
                }
            }
        }
    }

}
