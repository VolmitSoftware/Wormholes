package art.arcane.optics.stream;

import java.util.Arrays;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

public final class BrickCodec {
    public static final int LIGHT_UNIFORM = 0;
    public static final int LIGHT_RUNS = 1;
    public static final int LIGHT_RAW = 2;
    private static final int BODY_HEADER_BYTES = 3;
    private static final int MAX_LIGHT_VALUE = 15;

    private BrickCodec() {
    }

    public static Brick pack(int brickIndex, int[] cells) {
        if (cells.length != ViewStreamLimits.BRICK_CELLS) {
            throw new IllegalArgumentException("a brick holds " + ViewStreamLimits.BRICK_CELLS + " cells, got " + cells.length);
        }
        int first = cells[0];
        boolean uniform = true;
        for (int i = 1; i < cells.length; i++) {
            if (cells[i] != first) {
                uniform = false;
                break;
            }
        }
        if (uniform) {
            return Brick.single(brickIndex, first);
        }
        Int2IntOpenHashMap local = new Int2IntOpenHashMap(64);
        local.defaultReturnValue(-1);
        int[] palette = new int[64];
        int[] localIndices = new int[cells.length];
        int size = 0;
        for (int i = 0; i < cells.length; i++) {
            int id = cells[i];
            if (id < 0) {
                throw new IllegalArgumentException("palette id must not be negative: " + id);
            }
            int index = local.get(id);
            if (index < 0) {
                index = size;
                if (size == palette.length) {
                    palette = Arrays.copyOf(palette, size * 2);
                }
                palette[size++] = id;
                local.put(id, index);
            }
            localIndices[i] = index;
        }
        int bits = Brick.bitsFor(size);
        long[] packed = new long[Brick.packedLongs(bits)];
        for (int i = 0; i < localIndices.length; i++) {
            int bitOffset = i * bits;
            packed[bitOffset >>> 6] |= ((long) localIndices[i]) << (bitOffset & 63);
        }
        return new Brick(brickIndex, Brick.Encoding.PALETTED, bits, 0, ViewStreamLimits.PALETTE_AIR,
            Arrays.copyOf(palette, size), packed, null, null, null);
    }

    public static int[] unpack(Brick brick) {
        int[] cells = new int[ViewStreamLimits.BRICK_CELLS];
        unpackInto(brick, cells);
        return cells;
    }

    public static void unpackInto(Brick brick, int[] cells) {
        switch (brick.encoding()) {
            case EMPTY -> Arrays.fill(cells, ViewStreamLimits.PALETTE_AIR);
            case SINGLE -> Arrays.fill(cells, brick.singlePaletteId());
            case PALETTED -> {
                int bits = brick.bitsPerIndex();
                long mask = (1L << bits) - 1L;
                long[] packed = brick.packedIndices();
                int[] palette = brick.localPalette();
                int perLong = 64 / bits;
                int cell = 0;
                for (long word : packed) {
                    long remaining = word;
                    for (int slot = 0; slot < perLong; slot++) {
                        cells[cell++] = palette[(int) (remaining & mask)];
                        remaining >>>= bits;
                    }
                }
            }
        }
    }

    public static void write(ViewStreamWriter out, Brick brick) throws ViewStreamProtocolException {
        out.u16(brick.brickIndex());
        writeBody(out, brick);
    }

    public static void writeBody(ViewStreamWriter out, Brick brick) throws ViewStreamProtocolException {
        int start = out.size();
        out.u8(brick.encoding().id());
        out.u8(brick.bitsPerIndex());
        out.u8(brick.flags());
        switch (brick.encoding()) {
            case EMPTY -> {
            }
            case SINGLE -> out.varint(brick.singlePaletteId());
            case PALETTED -> {
                int[] palette = brick.localPalette();
                out.varint(palette.length);
                for (int id : palette) {
                    out.varint(id);
                }
                out.longs(brick.packedIndices());
            }
        }
        if (brick.hasLight()) {
            writeLightLayer(out, brick.blockLight());
            writeLightLayer(out, brick.skyLight());
        }
        if (brick.hasBlockEntities()) {
            Brick.BlockEntityCell[] cells = brick.blockEntities();
            if (cells.length > ViewStreamLimits.MAX_BRICK_BLOCK_ENTITIES) {
                throw new ViewStreamProtocolException("brick carries " + cells.length + " block entities");
            }
            out.u16(cells.length);
            for (Brick.BlockEntityCell cell : cells) {
                out.u16(cell.cellIndex());
                out.varint(cell.payload().length);
                out.bytes(cell.payload());
            }
        }
        if (out.size() - start > ViewStreamLimits.MAX_BRICK_BYTES) {
            throw new ViewStreamProtocolException("brick body exceeds " + ViewStreamLimits.MAX_BRICK_BYTES + " bytes");
        }
    }

    public static byte[] body(Brick brick) throws ViewStreamProtocolException {
        ViewStreamWriter out = new ViewStreamWriter(encodedSize(brick));
        writeBody(out, brick);
        return out.toByteArray();
    }

    public static Brick read(ViewStreamReader in) throws ViewStreamProtocolException {
        int brickIndex = in.u16();
        return readBody(in, brickIndex);
    }

    public static Brick readBody(ViewStreamReader in, int brickIndex) throws ViewStreamProtocolException {
        int start = in.position();
        Brick.Encoding encoding = Brick.Encoding.byId(in.u8());
        if (encoding == null) {
            throw new ViewStreamProtocolException("unknown brick encoding");
        }
        int bits = in.u8();
        int flags = in.u8();
        if ((flags & ~Brick.FLAG_MASK) != 0) {
            throw new ViewStreamProtocolException("unknown brick flags " + flags);
        }
        int single = ViewStreamLimits.PALETTE_AIR;
        int[] palette = null;
        long[] packed = null;
        switch (encoding) {
            case EMPTY -> {
                if (bits != 0) {
                    throw new ViewStreamProtocolException("empty brick with bits " + bits);
                }
            }
            case SINGLE -> {
                if (bits != 0) {
                    throw new ViewStreamProtocolException("single brick with bits " + bits);
                }
                single = in.varint(ViewStreamLimits.MAX_SESSION_PALETTE_SIZE - 1);
                if (single == ViewStreamLimits.PALETTE_AIR) {
                    throw new ViewStreamProtocolException("single brick of air must be EMPTY");
                }
            }
            case PALETTED -> {
                if (!Brick.validBits(bits)) {
                    throw new ViewStreamProtocolException("invalid bits per index " + bits);
                }
                int maxPalette = Math.min(1 << bits, ViewStreamLimits.BRICK_CELLS);
                int size = in.varint(maxPalette);
                if (size < 2) {
                    throw new ViewStreamProtocolException("paletted brick with " + size + " entries");
                }
                in.checkedCount(size, maxPalette, 1);
                palette = new int[size];
                for (int i = 0; i < size; i++) {
                    palette[i] = in.varint(ViewStreamLimits.MAX_SESSION_PALETTE_SIZE - 1);
                }
                int longs = Brick.packedLongs(bits);
                in.require(longs * 8);
                packed = in.longs(longs);
                long limit = (1L << bits) - 1L;
                if (size - 1 < limit) {
                    validateIndices(packed, bits, size);
                }
            }
        }
        byte[] blockLight = null;
        byte[] skyLight = null;
        if ((flags & Brick.FLAG_LIGHT) != 0) {
            blockLight = readLightLayer(in);
            skyLight = readLightLayer(in);
        }
        Brick.BlockEntityCell[] blockEntities = null;
        if ((flags & Brick.FLAG_BLOCK_ENTITIES) != 0) {
            int count = in.checkedCount(in.u16(), ViewStreamLimits.MAX_BRICK_BLOCK_ENTITIES, 3);
            if (count == 0) {
                throw new ViewStreamProtocolException("BLOCK_ENTITIES flag with zero entries");
            }
            blockEntities = new Brick.BlockEntityCell[count];
            int total = 0;
            for (int i = 0; i < count; i++) {
                int cellIndex = in.u16();
                if (cellIndex >= ViewStreamLimits.BRICK_CELLS) {
                    throw new ViewStreamProtocolException("block entity cell " + cellIndex + " outside the brick");
                }
                int length = in.varint(ViewStreamLimits.MAX_BLOCK_ENTITY_PAYLOAD_BYTES);
                total += length;
                if (total > ViewStreamLimits.MAX_BRICK_BLOCK_ENTITY_BYTES) {
                    throw new ViewStreamProtocolException("brick block entity payload exceeds " + ViewStreamLimits.MAX_BRICK_BLOCK_ENTITY_BYTES);
                }
                blockEntities[i] = new Brick.BlockEntityCell(cellIndex, in.bytes(length));
            }
        }
        if (in.position() - start > ViewStreamLimits.MAX_BRICK_BYTES) {
            throw new ViewStreamProtocolException("brick body exceeds " + ViewStreamLimits.MAX_BRICK_BYTES + " bytes");
        }
        return new Brick(brickIndex, encoding, bits, flags, single, palette, packed, blockLight, skyLight, blockEntities);
    }

    public static int encodedSize(Brick brick) {
        int size = BODY_HEADER_BYTES;
        switch (brick.encoding()) {
            case EMPTY -> {
            }
            case SINGLE -> size += ViewStreamWriter.varintSize(brick.singlePaletteId());
            case PALETTED -> {
                size += ViewStreamWriter.varintSize(brick.localPalette().length);
                for (int id : brick.localPalette()) {
                    size += ViewStreamWriter.varintSize(id);
                }
                size += brick.packedIndices().length * 8;
            }
        }
        if (brick.hasLight()) {
            size += lightLayerSize(brick.blockLight()) + lightLayerSize(brick.skyLight());
        }
        if (brick.hasBlockEntities()) {
            size += 2;
            for (Brick.BlockEntityCell cell : brick.blockEntities()) {
                size += 2 + ViewStreamWriter.varintSize(cell.payload().length) + cell.payload().length;
            }
        }
        return size;
    }

    public static int lightLayerSize(byte[] nibbles) {
        int uniform = uniformNibble(nibbles);
        if (uniform >= 0) {
            return 2;
        }
        int runs = runsSize(nibbles);
        return runs < ViewStreamLimits.LIGHT_NIBBLE_BYTES ? 1 + runs : 1 + ViewStreamLimits.LIGHT_NIBBLE_BYTES;
    }

    public static void writeLightLayer(ViewStreamWriter out, byte[] nibbles) throws ViewStreamProtocolException {
        int uniform = uniformNibble(nibbles);
        if (uniform >= 0) {
            out.u8(LIGHT_UNIFORM);
            out.u8(uniform);
            return;
        }
        if (runsSize(nibbles) >= ViewStreamLimits.LIGHT_NIBBLE_BYTES) {
            out.u8(LIGHT_RAW);
            out.bytes(nibbles);
            return;
        }
        out.u8(LIGHT_RUNS);
        out.varint(runCount(nibbles));
        int value = BrickLightSource.nibble(nibbles, 0);
        int length = 1;
        for (int cell = 1; cell < ViewStreamLimits.BRICK_CELLS; cell++) {
            int next = BrickLightSource.nibble(nibbles, cell);
            if (next == value) {
                length++;
                continue;
            }
            out.u8(value);
            out.varint(length);
            value = next;
            length = 1;
        }
        out.u8(value);
        out.varint(length);
    }

    public static byte[] readLightLayer(ViewStreamReader in) throws ViewStreamProtocolException {
        int mode = in.u8();
        switch (mode) {
            case LIGHT_UNIFORM -> {
                int value = in.u8();
                if (value > MAX_LIGHT_VALUE) {
                    throw new ViewStreamProtocolException("uniform light value " + value + " exceeds " + MAX_LIGHT_VALUE);
                }
                byte[] nibbles = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
                Arrays.fill(nibbles, (byte) (value | (value << 4)));
                return nibbles;
            }
            case LIGHT_RUNS -> {
                int runs = in.checkedCount(in.varint(ViewStreamLimits.BRICK_CELLS), ViewStreamLimits.BRICK_CELLS, 2);
                if (runs == 0) {
                    throw new ViewStreamProtocolException("light runs without a run");
                }
                byte[] nibbles = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
                int cell = 0;
                for (int run = 0; run < runs; run++) {
                    int value = in.u8();
                    if (value > MAX_LIGHT_VALUE) {
                        throw new ViewStreamProtocolException("light run value " + value + " exceeds " + MAX_LIGHT_VALUE);
                    }
                    int length = in.varint(ViewStreamLimits.BRICK_CELLS);
                    if (length == 0 || cell + length > ViewStreamLimits.BRICK_CELLS) {
                        throw new ViewStreamProtocolException("light runs do not fit the brick");
                    }
                    for (int end = cell + length; cell < end; cell++) {
                        BrickLightSource.setNibble(nibbles, cell, value);
                    }
                }
                if (cell != ViewStreamLimits.BRICK_CELLS) {
                    throw new ViewStreamProtocolException("light runs cover " + cell + " of " + ViewStreamLimits.BRICK_CELLS + " cells");
                }
                return nibbles;
            }
            case LIGHT_RAW -> {
                in.require(ViewStreamLimits.LIGHT_NIBBLE_BYTES);
                return in.bytes(ViewStreamLimits.LIGHT_NIBBLE_BYTES);
            }
            default -> throw new ViewStreamProtocolException("unknown light layer mode " + mode);
        }
    }

    private static int uniformNibble(byte[] nibbles) {
        byte first = nibbles[0];
        if ((first & 0x0F) != ((first >>> 4) & 0x0F)) {
            return -1;
        }
        for (int i = 1; i < nibbles.length; i++) {
            if (nibbles[i] != first) {
                return -1;
            }
        }
        return first & 0x0F;
    }

    private static int runCount(byte[] nibbles) {
        int runs = 1;
        int value = BrickLightSource.nibble(nibbles, 0);
        for (int cell = 1; cell < ViewStreamLimits.BRICK_CELLS; cell++) {
            int next = BrickLightSource.nibble(nibbles, cell);
            if (next != value) {
                runs++;
                value = next;
            }
        }
        return runs;
    }

    private static int runsSize(byte[] nibbles) {
        int size = 0;
        int runs = 0;
        int value = BrickLightSource.nibble(nibbles, 0);
        int length = 1;
        for (int cell = 1; cell < ViewStreamLimits.BRICK_CELLS; cell++) {
            int next = BrickLightSource.nibble(nibbles, cell);
            if (next == value) {
                length++;
                continue;
            }
            size += 1 + ViewStreamWriter.varintSize(length);
            runs++;
            value = next;
            length = 1;
        }
        size += 1 + ViewStreamWriter.varintSize(length);
        runs++;
        return size + ViewStreamWriter.varintSize(runs);
    }

    private static void validateIndices(long[] packed, int bits, int paletteSize) throws ViewStreamProtocolException {
        long mask = (1L << bits) - 1L;
        int perLong = 64 / bits;
        for (long word : packed) {
            long remaining = word;
            for (int slot = 0; slot < perLong; slot++) {
                if ((remaining & mask) >= paletteSize) {
                    throw new ViewStreamProtocolException("packed index outside the local palette");
                }
                remaining >>>= bits;
            }
        }
    }
}
