package art.arcane.optics.stream;

public interface ViewStreamExtension<X> {
    int firstId();

    int lastId();

    boolean serverbound(int id);

    boolean clientbound(int id);

    int id(X message);

    long capabilities();
}
