package art.arcane.wormholes.network.client;

public interface ClientViewTransport<P> {
    void send(P player, byte[] payload);

    void flush(P player);
}
