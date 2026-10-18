package art.arcane.wormholes.chunk.presend;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ChunkPreparedPacket<P> extends AutoCloseable {
    CompletionStage<Boolean> send(P player);

    default boolean ready() {
        return true;
    }

    default boolean valid() {
        return true;
    }

    @Override
    default void close() {
    }
}
