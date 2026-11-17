package art.arcane.wormholes.modded.seamless;

/**
 * Lets the chunk mutation mixin hear about {@code ChunkAccess.setUnsaved(true)}, which 1.20.1 declares only on the
 * superclass and so cannot be injected from a {@code LevelChunk} mixin.
 */
public interface UnsavedChunkHook {
    void wormholesMarkedUnsaved();
}
