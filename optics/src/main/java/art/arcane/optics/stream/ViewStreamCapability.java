package art.arcane.optics.stream;

import java.util.EnumSet;
import java.util.Set;

public enum ViewStreamCapability {
    PLATES(0),
    BRICK_CACHE(1),
    DEST_LIGHT(2),
    ENTITY_FRAMES(3),
    ATMOSPHERE(5),
    ZERO_COPY(6),
    CLIENT_RECURSION(7),
    CLIENT_MIRROR(8),
    CONFIG_PHASE(9),
    LINK_UNCOMPRESSED(10),
    VIEW_STATS(11),
    MESH_RENDER(12),
    ENTITY_EVENTS(13),
    LOCAL_MESH(14),
    MESH_REUSE(15),
    ENTITY_SELF(18);

    public static final long NONE = 0L;
    public static final int FIRST_EXTENSION_BIT = 32;
    public static final int EXTENSION_BITS = Long.SIZE - FIRST_EXTENSION_BIT;
    public static final long EXTENSIONS = -1L << FIRST_EXTENSION_BIT;
    public static final long ALL = projectionMask() | EXTENSIONS;

    private final int bit;

    ViewStreamCapability(int bit) {
        this.bit = bit;
    }

    public int bit() {
        return bit;
    }

    public long mask() {
        return 1L << bit;
    }

    public boolean in(long set) {
        return (set & mask()) != 0L;
    }

    public static long extension(int index) {
        if (index < 0 || index >= EXTENSION_BITS) {
            throw new IllegalArgumentException("extension capability index " + index + " is outside 0.." + (EXTENSION_BITS - 1));
        }
        return 1L << (FIRST_EXTENSION_BIT + index);
    }

    public static long of(ViewStreamCapability... capabilities) {
        long set = NONE;
        for (ViewStreamCapability capability : capabilities) {
            set |= capability.mask();
        }
        return set;
    }

    public static long intersection(long left, long right) {
        return left & right & ALL;
    }

    public static Set<ViewStreamCapability> decode(long set) {
        EnumSet<ViewStreamCapability> result = EnumSet.noneOf(ViewStreamCapability.class);
        for (ViewStreamCapability capability : values()) {
            if (capability.in(set)) {
                result.add(capability);
            }
        }
        return result;
    }

    private static long projectionMask() {
        long set = NONE;
        for (ViewStreamCapability capability : values()) {
            set |= capability.mask();
        }
        return set;
    }
}
