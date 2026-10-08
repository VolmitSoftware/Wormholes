package art.arcane.wormholes.clientgametest;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Set;

public final class LoadedChunks {
    private static final Set<Key> LOADED = new HashSet<>();

    private LoadedChunks() {
    }

    public static synchronized void loaded(ServerLevel level, ChunkPos pos) {
        LOADED.add(new Key(level.dimension(), System.identityHashCode(level), pos.pack()));
    }

    static synchronized boolean everLoaded(ServerLevel level, int chunkX, int chunkZ) {
        return LOADED.contains(new Key(level.dimension(), System.identityHashCode(level), ChunkPos.pack(chunkX, chunkZ)));
    }

    private record Key(ResourceKey<Level> dimension, int level, long chunk) {
    }
}
