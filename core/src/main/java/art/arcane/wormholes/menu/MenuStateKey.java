package art.arcane.wormholes.menu;

import java.util.Objects;

public record MenuStateKey<T>(String name, Class<T> type) {
    public MenuStateKey {
        MenuText.id(name, "state key");
        Objects.requireNonNull(type, "type");
    }
}
