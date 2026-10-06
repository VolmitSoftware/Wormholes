package art.arcane.optics.stream;

import java.util.Arrays;
import java.util.Objects;

public record Brick(int brickIndex,
                    Encoding encoding,
                    int bitsPerIndex,
                    int flags,
                    int singlePaletteId,
                    int[] localPalette,
                    long[] packedIndices,
                    byte[] blockLight,
                    byte[] skyLight,
                    BlockEntityCell[] blockEntities) {
    public static final int FLAG_LIGHT = 1;
    public static final int FLAG_BLOCK_ENTITIES = 1 << 1;
    public static final int FLAG_MASK = FLAG_LIGHT | FLAG_BLOCK_ENTITIES;
    private static final int[] NO_PALETTE = new int[0];
    private static final long[] NO_INDICES = new long[0];
    private static final BlockEntityCell[] NO_BLOCK_ENTITIES = new BlockEntityCell[0];

    public Brick {
        Objects.requireNonNull(encoding, "encoding");
        localPalette = localPalette == null ? NO_PALETTE : localPalette;
        packedIndices = packedIndices == null ? NO_INDICES : packedIndices;
        blockEntities = blockEntities == null ? NO_BLOCK_ENTITIES : blockEntities;
        if (encoding == Encoding.PALETTED) {
            if (!validBits(bitsPerIndex)) {
                throw new IllegalArgumentException("bitsPerIndex must be 1, 2, 4, 8 or 16: " + bitsPerIndex);
            }
            if (packedIndices.length != packedLongs(bitsPerIndex)) {
                throw new IllegalArgumentException("packed index length " + packedIndices.length + " does not match " + bitsPerIndex + " bits");
            }
            if (localPalette.length < 2 || localPalette.length > (1 << bitsPerIndex)) {
                throw new IllegalArgumentException("local palette of " + localPalette.length + " does not fit " + bitsPerIndex + " bits");
            }
        } else if (bitsPerIndex != 0) {
            throw new IllegalArgumentException("bitsPerIndex must be 0 for " + encoding);
        }
        boolean light = (flags & FLAG_LIGHT) != 0;
        if (light != (blockLight != null)) {
            throw new IllegalArgumentException("LIGHT flag does not match the light payload");
        }
        if (light && (blockLight.length != ViewStreamLimits.LIGHT_NIBBLE_BYTES || skyLight == null
            || skyLight.length != ViewStreamLimits.LIGHT_NIBBLE_BYTES)) {
            throw new IllegalArgumentException("light nibbles must be " + ViewStreamLimits.LIGHT_NIBBLE_BYTES + " bytes each");
        }
        if (((flags & FLAG_BLOCK_ENTITIES) != 0) != (blockEntities.length > 0)) {
            throw new IllegalArgumentException("BLOCK_ENTITIES flag does not match the block entity payload");
        }
        if ((flags & ~FLAG_MASK) != 0) {
            throw new IllegalArgumentException("unknown brick flags " + flags);
        }
    }

    public static Brick empty(int brickIndex) {
        return new Brick(brickIndex, Encoding.EMPTY, 0, 0, ViewStreamLimits.PALETTE_AIR, null, null, null, null, null);
    }

    public static Brick single(int brickIndex, int paletteId) {
        if (paletteId == ViewStreamLimits.PALETTE_AIR) {
            return empty(brickIndex);
        }
        return new Brick(brickIndex, Encoding.SINGLE, 0, 0, paletteId, null, null, null, null, null);
    }

    public static boolean validBits(int bits) {
        return bits == 1 || bits == 2 || bits == 4 || bits == 8 || bits == 16;
    }

    public static int bitsFor(int localPaletteSize) {
        if (localPaletteSize <= 2) {
            return 1;
        }
        if (localPaletteSize <= 4) {
            return 2;
        }
        if (localPaletteSize <= 16) {
            return 4;
        }
        if (localPaletteSize <= 256) {
            return 8;
        }
        return 16;
    }

    public static int packedLongs(int bits) {
        return ViewStreamLimits.BRICK_CELLS * bits / 64;
    }

    public boolean hasLight() {
        return (flags & FLAG_LIGHT) != 0;
    }

    public boolean hasBlockEntities() {
        return (flags & FLAG_BLOCK_ENTITIES) != 0;
    }

    public boolean isEmpty() {
        return encoding == Encoding.EMPTY;
    }

    public int paletteIdAt(int cellIndex) {
        return switch (encoding) {
            case EMPTY -> ViewStreamLimits.PALETTE_AIR;
            case SINGLE -> singlePaletteId;
            case PALETTED -> localPalette[localIndexAt(cellIndex)];
        };
    }

    public int localIndexAt(int cellIndex) {
        int bitOffset = cellIndex * bitsPerIndex;
        long word = packedIndices[bitOffset >>> 6];
        return (int) ((word >>> (bitOffset & 63)) & ((1L << bitsPerIndex) - 1L));
    }

    public Brick withIndex(int newIndex) {
        return new Brick(newIndex, encoding, bitsPerIndex, flags, singlePaletteId, localPalette, packedIndices, blockLight, skyLight, blockEntities);
    }

    public Brick withLight(byte[] block, byte[] sky) {
        int newFlags = block == null ? flags & ~FLAG_LIGHT : flags | FLAG_LIGHT;
        return new Brick(brickIndex, encoding, bitsPerIndex, newFlags, singlePaletteId, localPalette, packedIndices, block, sky, blockEntities);
    }

    public Brick withBlockEntities(BlockEntityCell[] cells) {
        BlockEntityCell[] safe = cells == null ? NO_BLOCK_ENTITIES : cells;
        int newFlags = safe.length == 0 ? flags & ~FLAG_BLOCK_ENTITIES : flags | FLAG_BLOCK_ENTITIES;
        return new Brick(brickIndex, encoding, bitsPerIndex, newFlags, singlePaletteId, localPalette, packedIndices, blockLight, skyLight, safe);
    }

    public Brick stripped() {
        return new Brick(brickIndex, encoding, bitsPerIndex, 0, singlePaletteId, localPalette, packedIndices, null, null, null);
    }

    public boolean sameCells(Brick other) {
        return encoding == other.encoding && bitsPerIndex == other.bitsPerIndex && singlePaletteId == other.singlePaletteId
            && Arrays.equals(localPalette, other.localPalette) && Arrays.equals(packedIndices, other.packedIndices);
    }

    public boolean sameExtras(Brick other) {
        return flags == other.flags && Arrays.equals(blockLight, other.blockLight) && Arrays.equals(skyLight, other.skyLight)
            && Arrays.equals(blockEntities, other.blockEntities);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Brick that)) {
            return false;
        }
        return brickIndex == that.brickIndex && sameCells(that) && sameExtras(that);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(brickIndex, encoding, bitsPerIndex, flags, singlePaletteId);
        result = result * 31 + Arrays.hashCode(localPalette);
        result = result * 31 + Arrays.hashCode(packedIndices);
        result = result * 31 + Arrays.hashCode(blockLight);
        result = result * 31 + Arrays.hashCode(skyLight);
        return result * 31 + Arrays.hashCode(blockEntities);
    }

    @Override
    public String toString() {
        return "Brick[" + brickIndex + " " + encoding + " bits=" + bitsPerIndex + " flags=" + flags + "]";
    }

    public enum Encoding {
        EMPTY(0),
        SINGLE(1),
        PALETTED(2);

        private final int id;

        Encoding(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static Encoding byId(int id) {
            return switch (id) {
                case 0 -> EMPTY;
                case 1 -> SINGLE;
                case 2 -> PALETTED;
                default -> null;
            };
        }
    }

    public record BlockEntityCell(int cellIndex, byte[] payload) {
        public BlockEntityCell {
            Objects.requireNonNull(payload, "payload");
            if (cellIndex < 0 || cellIndex >= ViewStreamLimits.BRICK_CELLS) {
                throw new IllegalArgumentException("cell index out of brick: " + cellIndex);
            }
            if (payload.length > ViewStreamLimits.MAX_BLOCK_ENTITY_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("block entity payload of " + payload.length + " bytes exceeds the cap");
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof BlockEntityCell that)) {
                return false;
            }
            return cellIndex == that.cellIndex && Arrays.equals(payload, that.payload);
        }

        @Override
        public int hashCode() {
            return cellIndex * 31 + Arrays.hashCode(payload);
        }

        @Override
        public String toString() {
            return "BlockEntityCell[" + cellIndex + " " + payload.length + "b]";
        }
    }
}
