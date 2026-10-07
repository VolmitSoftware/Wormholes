package art.arcane.optics.stream;

public interface ViewStreamTransport<P> {
    void send(P player, byte[] payload);

    void flush(P player);
}
