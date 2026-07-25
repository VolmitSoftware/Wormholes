package art.arcane.wormholes.chunk;

import java.util.OptionalDouble;

public interface ChunkSendRateAccessor {
    boolean available();

    String describe();

    OptionalDouble read(ChunkSendRateLimit limit);

    boolean write(ChunkSendRateLimit limit, double value);
}
