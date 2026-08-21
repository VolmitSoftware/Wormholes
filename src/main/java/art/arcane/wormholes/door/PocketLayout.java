package art.arcane.wormholes.door;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * World-independent, deterministic layout of one protected pocket room.
 *
 * <p>The room is a cube anchored at its minimum corner. Its edge length comes
 * from the pocket's own {@link PocketShell}, so resizing a pocket moves only the
 * maximum walls, the ceiling, and the return door.</p>
 */
public record PocketLayout(PocketSpace space) {
    public static final int CHUNK_SIZE = 16;
    public static final float ENTRY_YAW = 0.0F;
    public static final float ENTRY_PITCH = 0.0F;

    public PocketLayout {
        Objects.requireNonNull(space, "space");
    }

    /** Edge length of the room in blocks, shell included. */
    public int size() {
        return space.shell().size();
    }

    public int minX() {
        return alignedMinimum(Math.subtractExact(space.centerX(), PocketAllocator.CHUNK_CENTER_OFFSET));
    }

    public int maxX() {
        return Math.addExact(minX(), size() - 1);
    }

    public int minZ() {
        return alignedMinimum(Math.subtractExact(space.centerZ(), PocketAllocator.CHUNK_CENTER_OFFSET));
    }

    public int maxZ() {
        return Math.addExact(minZ(), size() - 1);
    }

    public int minY() {
        return Math.subtractExact(space.centerY(), 1);
    }

    public int maxY() {
        return Math.addExact(minY(), size() - 1);
    }

    /** Distance from the minimum wall to the return door, centred on the maximum-Z wall. */
    public int returnDoorCenterOffset() {
        return size() / 2 - 1;
    }

    public PocketBlockPosition returnDoorLower() {
        return new PocketBlockPosition(
            Math.addExact(minX(), returnDoorCenterOffset()),
            Math.addExact(minY(), 1),
            maxZ()
        );
    }

    public PocketBlockPosition returnDoorUpper() {
        PocketBlockPosition lower = returnDoorLower();
        return new PocketBlockPosition(lower.x(), Math.addExact(lower.y(), 1), lower.z());
    }

    public PocketBlockPosition returnDoorSupport() {
        PocketBlockPosition lower = returnDoorLower();
        return new PocketBlockPosition(lower.x(), Math.subtractExact(lower.y(), 1), lower.z());
    }

    public PocketEntryCoordinates entry() {
        PocketBlockPosition lower = returnDoorLower();
        return new PocketEntryCoordinates(
            lower.x() + 0.5D,
            lower.y(),
            lower.z() - 0.5D,
            ENTRY_YAW,
            ENTRY_PITCH
        );
    }

    /** Stable internal return-door identity derived solely from the pocket ID. */
    public DoorItemIdentity returnDoorIdentity() {
        String seed = "wormholes:pocket-return-door:v1:" + space.spaceId();
        UUID itemId = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
        return DoorItemIdentity.returnDoor(itemId, space.spaceId());
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX() && x <= maxX()
            && y >= minY() && y <= maxY()
            && z >= minZ() && z <= maxZ();
    }

    public boolean isShellBlock(int x, int y, int z) {
        return contains(x, y, z)
            && (x == minX() || x == maxX()
                || y == minY() || y == maxY()
                || z == minZ() || z == maxZ());
    }

    public boolean isInteriorBlock(int x, int y, int z) {
        return contains(x, y, z) && !isShellBlock(x, y, z);
    }

    public boolean isProtected(int x, int y, int z) {
        return isShellBlock(x, y, z);
    }

    /**
     * Visits every shell block exactly once without walking the room's interior.
     *
     * <p>A full-volume walk is cubic in the room edge; at the largest supported
     * size that is millions of wasted iterations per provision and per entry
     * check.</p>
     */
    public void forEachShellBlock(BlockVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        int minX = minX();
        int maxX = maxX();
        int minY = minY();
        int maxY = maxY();
        int minZ = minZ();
        int maxZ = maxZ();
        for (int y = minY; y <= maxY; y++) {
            boolean cap = y == minY || y == maxY;
            for (int x = minX; x <= maxX; x++) {
                if (cap || x == minX || x == maxX) {
                    for (int z = minZ; z <= maxZ; z++) {
                        visitor.visit(x, y, z);
                    }
                    continue;
                }
                visitor.visit(x, y, minZ);
                visitor.visit(x, y, maxZ);
            }
        }
    }

    private static int alignedMinimum(int coordinate) {
        return Math.multiplyExact(Math.floorDiv(coordinate, CHUNK_SIZE), CHUNK_SIZE);
    }

    @FunctionalInterface
    public interface BlockVisitor {
        void visit(int x, int y, int z);
    }
}
