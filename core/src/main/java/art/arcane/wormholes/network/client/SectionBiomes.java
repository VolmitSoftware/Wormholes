package art.arcane.wormholes.network.client;

import java.util.List;
import java.util.Arrays;
import java.util.Objects;

public record SectionBiomes(List<String> palette, byte[] indices) {
    public static final SectionBiomes NONE = new SectionBiomes(List.of(), new byte[0]);
    public static final int CELLS = 64;

    public SectionBiomes {
        palette = List.copyOf(palette);
        indices = Objects.requireNonNull(indices, "indices").clone();
        if (palette.size() > CELLS || indices.length != (palette.size() > 1 ? CELLS : 0)) {
            throw new IllegalArgumentException("invalid section biome palette or indices");
        }
        for (String key : palette) {
            if (key.isBlank() || key.length() > 256) {
                throw new IllegalArgumentException("invalid biome key " + key);
            }
        }
        for (byte index : indices) {
            if (Byte.toUnsignedInt(index) >= palette.size()) {
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
        return palette.get(palette.size() == 1 ? 0 : Byte.toUnsignedInt(indices[cell]));
    }
}
