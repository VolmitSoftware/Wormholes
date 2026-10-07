package art.arcane.optics.fidelity;

import java.io.IOException;

public interface TagAccess<T> {
    Iterable<String> names(T compound);
    T get(T compound, String name);
    T compound();
    void put(T compound, String name, T value);
    Iterable<? extends T> list(T value);
    boolean contains(T compound, String name);
    byte[] encode(T compound) throws IOException;
}
