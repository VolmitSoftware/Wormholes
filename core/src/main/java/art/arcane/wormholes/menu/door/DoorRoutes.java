package art.arcane.wormholes.menu.door;

import java.util.UUID;

import art.arcane.wormholes.menu.MenuRoute;

public final class DoorRoutes {
    public static final String ACCESS_CONTROLLER = "door.access";
    public static final String ACCESS = "door.access";
    public static final String DOOR = "door";

    private DoorRoutes() {
    }

    public static MenuRoute access(UUID doorId) {
        return MenuRoute.of(ACCESS_CONTROLLER, ACCESS).with(DOOR, doorId.toString());
    }

    public static UUID doorId(MenuRoute route) {
        return route.uuid(DOOR);
    }
}
