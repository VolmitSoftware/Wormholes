package art.arcane.optics.stream;

import art.arcane.optics.fidelity.BlockEntitySample;

public final class ViewStreamLimits {
    public static final int TRAVEL_HASH_BYTES = 32;
    public static final int TRAVEL_REUSE_BYTES = 74;
    public static final int MAX_TRAVEL_REUSE_PROBES_PER_TICK = 8;
    public static final int MAX_TRAVEL_CHUNKS = 1089;
    public static final int MAX_TRAVEL_CHUNK_BYTES = 2 * 1024 * 1024;
    public static final int MAX_TRAVEL_BYTES = 64 * 1024 * 1024;
    public static final int TRAVEL_FRAGMENT_BYTES = 48 * 1024;
    public static final int MAX_TRAVEL_EXPIRY_MILLIS = 300_000;
    public static final int WIRE_VERSION = 6;

    public static final int S2C_HEADER_BYTES = 6;
    public static final int C2S_HEADER_BYTES = 1;
    public static final int FLAG_DEFLATED = 1;
    public static final int FLAG_LAST = 1 << 1;
    public static final int FLAG_RESERVED = 1 << 2;
    public static final int FLAG_MASK = FLAG_DEFLATED | FLAG_LAST | FLAG_RESERVED;

    public static final int DEFAULT_MAX_FRAME_BYTES = 512 * 1024;
    public static final int MIN_MAX_FRAME_BYTES = 64 * 1024;
    public static final int HARD_MAX_FRAME_BYTES = 1024 * 1024;
    public static final int MAX_C2S_BYTES = 32767;
    public static final int MAX_C2S_MESSAGES_PER_SECOND = 512;
    public static final int C2S_VIOLATION_LIMIT = 3;
    public static final long C2S_VIOLATION_WINDOW_MILLIS = 10_000L;
    public static final int DEFLATE_THRESHOLD_BYTES = 256;
    public static final int MAX_STRING_BYTES = 256;

    public static final int BRICK_EDGE = 16;
    public static final int BRICK_CELLS = BRICK_EDGE * BRICK_EDGE * BRICK_EDGE;
    public static final int LIGHT_NIBBLE_BYTES = BRICK_CELLS / 2;
    public static final int MAX_BRICKS_PER_PLATE = 65535;
    public static final int MAX_BRICK_BLOCK_ENTITIES = 512;
    public static final int MAX_BRICK_BLOCK_ENTITY_BYTES = 32 * 1024;
    public static final int MAX_BLOCK_ENTITY_PAYLOAD_BYTES = BlockEntitySample.MAX_NBT_BYTES + MAX_STRING_BYTES + 4;
    public static final int MAX_BRICK_BYTES = 48 * 1024;
    public static final int MAX_SECTION_BOX_EDGE = 255;

    public static final int PALETTE_AIR = 0;
    public static final int PALETTE_OCCLUDED = 1;
    public static final int PALETTE_BACKING = 2;
    public static final int RESERVED_PALETTE_IDS = 3;
    public static final int MAX_PALETTE_ENTRIES_PER_MESSAGE = 65535;
    public static final int MAX_SESSION_PALETTE_SIZE = 1 << 20;

    public static final int SPARSE_PATCH_MAX_CELLS = 64;
    public static final int MAX_PATCH_OPS = 65535;
    public static final int MAX_ENTITIES_PER_FRAME = 255;
    public static final int MAX_PRESENT_IDS_PER_FRAME = 1024;
    public static final int PRESENCE_UNCHANGED = 0xFFFF;
    public static final int MAX_ENTITY_VISUAL_BYTES = 16 * 1024;
    public static final int MAX_FX_EMITTERS = 255;
    public static final int WORLD_FX_KEY = 0;
    public static final int MAX_APERTURE_MASK_WORDS = 1024;
    public static final int MAX_NESTED_GEOMETRY = 16;
    public static final int MAX_LINKED_GEOMETRY_DEPTH = 4;
    public static final int MAX_MIRROR_REFLECTIONS = 4;
    public static final int MAX_GEOMETRY_DEPTH = 6;
    public static final int MAX_BRICK_MISS_WORDS = (MAX_BRICKS_PER_PLATE + 63) / 64;
    public static final int MAX_BRICK_MISS_PLATES = 255;

    public static final int DEFAULT_TICK_RATE = 20;
    public static final int DEFAULT_ACK_WINDOW_FRAMES = 8;
    public static final int DEFAULT_HELLO_GRACE_MILLIS = 100;
    public static final int PLAY_PHASE_PENDING_TICKS = 10;
    public static final int VIEW_STATS_MIN_INTERVAL_MILLIS = 5000;
    public static final int VIEW_STATS_JITTER_MILLIS = 1000;
    public static final long HANDOFF_EXPIRY_NANOS = 30_000_000_000L;

    private ViewStreamLimits() {
    }

    public static int clampMaxFrameBytes(int requested) {
        return Math.max(MIN_MAX_FRAME_BYTES, Math.min(HARD_MAX_FRAME_BYTES, requested));
    }

    public static int brickCellIndex(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    public static int brickCellX(int cellIndex) {
        return cellIndex & 15;
    }

    public static int brickCellY(int cellIndex) {
        return (cellIndex >> 8) & 15;
    }

    public static int brickCellZ(int cellIndex) {
        return (cellIndex >> 4) & 15;
    }
}
