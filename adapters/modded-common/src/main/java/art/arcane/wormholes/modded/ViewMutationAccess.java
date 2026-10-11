package art.arcane.wormholes.modded;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ViewMutationAccess {
    private ViewMutationAccess() {
    }

    public interface Chunk {
        boolean wormholesMutationActive();

        boolean wormholesMutatingBlock();

        void wormholesBlockMutated(int section, int x, int y, int z);

        void wormholesColumnMutated();
    }

    public interface Section {
        void wormholesBind(LevelChunk chunk, int index);

        void wormholesUnbind(LevelChunk chunk);

        boolean wormholesPaletteActive();

        void wormholesPaletteMutated(Object palette, int x, int y, int z);

        void wormholesPaletteReplaced(Object palette);
    }

    public interface Palette {
        void wormholesBind(LevelChunkSection section);
    }

    public static boolean sameContents(LevelChunkSection first, LevelChunkSection second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (first.getBlockState(x, y, z) != second.getBlockState(x, y, z)) {
                        return false;
                    }
                }
            }
        }
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    if (!first.getNoiseBiome(x, y, z).equals(second.getNoiseBiome(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
