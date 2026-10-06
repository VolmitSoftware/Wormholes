package art.arcane.optics.stream;

public interface ClientViewTransport<P> {
    void send(P player, byte[] payload);

    void flush(P player);
}
