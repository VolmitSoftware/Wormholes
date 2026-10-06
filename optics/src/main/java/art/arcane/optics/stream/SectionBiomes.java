package art.arcane.optics.stream;

import java.util.List;
import java.util.Arrays;
import java.util.Objects;

public record SectionBiomes(List<String> palette, byte[] indices) {
    public static final SectionBiomes NONE = new SectionBiomes(List.of(), new byte[0]);
    public static final int PADDING = 8;
    public static final int WIDTH = 8;
    public static final int CELLS = WIDTH * WIDTH * WIDTH;
    public static final int INDEX_BYTES = CELLS * 2;

    public SectionBiomes {
        palette = List.copyOf(palette);
        indices = Objects.requireNonNull(indices, "indices").clone();
        if (palette.size() > CELLS || indices.length != (palette.size() > 1 ? INDEX_BYTES : 0)) {
            throw new IllegalArgumentException("invalid section biome palette or indices");
        }
        for (String key : palette) {
            if (key.isBlank() || key.length() > 256) {
                throw new IllegalArgumentException("invalid biome key " + key);
            }
        }
        for (int offset = 0; offset < indices.length; offset += 2) {
            if ((Byte.toUnsignedInt(indices[offset]) | Byte.toUnsignedInt(indices[offset + 1]) << 8) >= palette.size()) {
                throw new IllegalArgumentException("biome index exceeds palette");
            }
        }
    }

    @Override
    public byte[] indices() {
        return indices.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SectionBiomes biomes && palette.equals(biomes.palette) && Arrays.equals(indices, biomes.indices);
    }

    @Override
    public int hashCode() {
        return 31 * palette.hashCode() + Arrays.hashCode(indices);
    }

    public String biome(int cell) {
        if (cell < 0 || cell >= CELLS || palette.isEmpty()) {
            return null;
        }
        return palette.get(palette.size() == 1 ? 0
            : Byte.toUnsignedInt(indices[cell * 2]) | Byte.toUnsignedInt(indices[cell * 2 + 1]) << 8);
    }

    public static int cell(int x, int y, int z) {
        int quartX = (x + PADDING) >> 2;
        int quartY = (y + PADDING) >> 2;
        int quartZ = (z + PADDING) >> 2;
        return quartX < 0 || quartX >= WIDTH || quartY < 0 || quartY >= WIDTH || quartZ < 0 || quartZ >= WIDTH
            ? -1 : (quartY * WIDTH + quartZ) * WIDTH + quartX;
    }
}
