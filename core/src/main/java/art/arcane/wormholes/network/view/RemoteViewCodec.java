package art.arcane.wormholes.network.view;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

public interface RemoteViewCodec<B, M, E> {
    B parseBlock(String state);
    B air();
    B occluded();
    B[] palette(int size);
    boolean blockEntityCandidate(String state);
    List<M> metadata(byte[] data) throws IOException;
    List<E> equipment(byte[] data) throws IOException;
    void warning(String message, Throwable error);
    void debug(Supplier<String> message);
}
