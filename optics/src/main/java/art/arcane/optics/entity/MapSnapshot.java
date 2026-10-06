package art.arcane.optics.entity;


import java.nio.ByteBuffer;
import java.util.Arrays;




public final class MapSnapshot {
    public static final int WIDTH = 128;
    public static final int HEIGHT = 128;
    public static final int PIXEL_COUNT = WIDTH * HEIGHT;

    private static final int MAGIC = 0x574D4150;
    private static final byte VERSION = 1;
    private static final int FLAG_TRACKING = 1;
    private static final int FLAG_LOCKED = 1 << 1;
    private static final int KNOWN_FLAGS = FLAG_TRACKING | FLAG_LOCKED;
    private static final int ENCODED_LENGTH = Integer.BYTES + Byte.BYTES + Integer.BYTES
        + Byte.BYTES + Byte.BYTES + PIXEL_COUNT;

    private final int sourceMapId;
    private final byte scale;
    private final boolean tracking;
    private final boolean locked;
    private final byte[] pixels;

    public MapSnapshot(int sourceMapId,
                            byte scale,
                            boolean tracking,
                            boolean locked,
                            byte[] pixels) {
        if (scale < 0 || scale > 4) {
            throw new IllegalArgumentException("Map scale must be between 0 and 4");
        }
        if (pixels == null || pixels.length != PIXEL_COUNT) {
            throw new IllegalArgumentException("Map pixel payload must contain exactly " + PIXEL_COUNT + " bytes");
        }
        this.sourceMapId = sourceMapId;
        this.scale = scale;
        this.tracking = tracking;
        this.locked = locked;
        this.pixels = pixels.clone();
    }

    public int sourceMapId() {
        return sourceMapId;
    }

    public byte scale() {
        return scale;
    }

    public boolean tracking() {
        return tracking;
    }

    public boolean locked() {
        return locked;
    }

    public byte[] pixels() {
        return pixels.clone();
    }

    public byte[] encode() {
        ByteBuffer encoded = ByteBuffer.allocate(ENCODED_LENGTH);
        encoded.putInt(MAGIC);
        encoded.put(VERSION);
        encoded.putInt(sourceMapId);
        encoded.put(scale);
        int flags = (tracking ? FLAG_TRACKING : 0) | (locked ? FLAG_LOCKED : 0);
        encoded.put((byte) flags);
        encoded.put(pixels);
        return encoded.array();
    }

    public static MapSnapshot decode(byte[] encoded) {
        if (encoded == null || encoded.length != ENCODED_LENGTH) {
            throw new IllegalArgumentException("Projected map payload has an invalid length");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded);
        if (input.getInt() != MAGIC) {
            throw new IllegalArgumentException("Projected map payload has an invalid magic value");
        }
        if (input.get() != VERSION) {
            throw new IllegalArgumentException("Projected map payload has an unsupported version");
        }
        int sourceMapId = input.getInt();
        byte scale = input.get();
        int flags = input.get() & 0xFF;
        if ((flags & ~KNOWN_FLAGS) != 0) {
            throw new IllegalArgumentException("Projected map payload has unsupported flags");
        }
        byte[] pixels = new byte[PIXEL_COUNT];
        input.get(pixels);
        return new MapSnapshot(sourceMapId, scale,
            (flags & FLAG_TRACKING) != 0,
            (flags & FLAG_LOCKED) != 0,
            pixels);
    }

    public MapSnapshot mirrorHorizontally() {
        byte[] mirrored = new byte[PIXEL_COUNT];
        for (int y = 0; y < HEIGHT; y++) {
            int row = y * WIDTH;
            for (int x = 0; x < WIDTH; x++) {
                mirrored[row + x] = pixels[row + (WIDTH - 1 - x)];
            }
        }
        return new MapSnapshot(sourceMapId, scale, tracking, locked, mirrored);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MapSnapshot mapData)) {
            return false;
        }
        return sourceMapId == mapData.sourceMapId
            && scale == mapData.scale
            && tracking == mapData.tracking
            && locked == mapData.locked
            && Arrays.equals(pixels, mapData.pixels);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(sourceMapId);
        result = (31 * result) + Byte.hashCode(scale);
        result = (31 * result) + Boolean.hashCode(tracking);
        result = (31 * result) + Boolean.hashCode(locked);
        result = (31 * result) + Arrays.hashCode(pixels);
        return result;
    }

    @Override
    public String toString() {
        return "ProjectedMapData[sourceMapId=" + sourceMapId
            + ", scale=" + scale
            + ", tracking=" + tracking
            + ", locked=" + locked
            + ", pixels=" + pixels.length + "]";
    }

}
