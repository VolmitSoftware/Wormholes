package art.arcane.wormholes.menu;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class MenuState {
    private final Map<MenuStateKey<?>, Object> values = new HashMap<>();

    public <T> Optional<T> get(MenuStateKey<T> key) {
        return Optional.ofNullable(key.type().cast(values.get(key)));
    }

    public <T> T getOrDefault(MenuStateKey<T> key, T fallback) {
        Object value = values.get(key);
        return value == null ? fallback : key.type().cast(value);
    }

    public <T> void put(MenuStateKey<T> key, T value) {
        values.put(key, key.type().cast(Objects.requireNonNull(value, key.name())));
    }

    public void remove(MenuStateKey<?> key) {
        values.remove(key);
    }
}
