package art.arcane.wormholes.menu;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MenuRegistry {
    private final Map<String, MenuController> controllers;

    public MenuRegistry(List<MenuController> controllers) {
        Map<String, MenuController> byId = new HashMap<>(controllers.size() * 2);
        for (MenuController controller : controllers) {
            MenuController present = Objects.requireNonNull(controller, "controller");
            if (byId.putIfAbsent(present.id(), present) != null) {
                throw new IllegalArgumentException("duplicate menu controller " + present.id());
            }
        }
        this.controllers = Map.copyOf(byId);
    }

    public MenuController require(String controllerId) {
        MenuController controller = controllers.get(controllerId);
        if (controller == null) {
            throw new IllegalArgumentException("no menu controller is registered as " + controllerId);
        }
        return controller;
    }
}
