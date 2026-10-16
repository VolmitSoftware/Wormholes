package art.arcane.wormholes.menu;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record MenuRoute(String controllerId, String screen, Map<String, String> params) {
    public MenuRoute {
        controllerId = MenuText.id(controllerId, "controller id");
        screen = MenuText.id(screen, "screen");
        params = Map.copyOf(Objects.requireNonNull(params, "params"));
    }

    public static MenuRoute of(String controllerId, String screen) {
        return new MenuRoute(controllerId, screen, Map.of());
    }

    public MenuRoute with(String key, String value) {
        Map<String, String> next = new HashMap<>(params);
        next.put(key, value);
        return new MenuRoute(controllerId, screen, Map.copyOf(next));
    }

    public UUID uuid(String key) {
        return UUID.fromString(Objects.requireNonNull(params.get(key), key));
    }

    public boolean sameScreen(MenuRoute other) {
        return controllerId.equals(other.controllerId) && screen.equals(other.screen) && params.equals(other.params);
    }
}
