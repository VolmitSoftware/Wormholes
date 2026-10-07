package art.arcane.optics.stream;

public interface ViewStreamTransport<O> {
    void send(O player, byte[] payload);

    void flush(O player);
}
