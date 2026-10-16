package art.arcane.wormholes.menu.atlas;

import art.arcane.wormholes.atlas.AtlasModel;
import art.arcane.wormholes.menu.MenuRoute;

public final class AtlasRoutes {
    public static final String CONTROLLER = "atlas";
    public static final String LIST = "atlas.list";
    public static final String FILTER = "filter";
    public static final String PERMISSION = "wormholes.atlas";

    private AtlasRoutes() {
    }

    public static MenuRoute list(AtlasModel.Filter filter) {
        return MenuRoute.of(CONTROLLER, LIST).with(FILTER, filter.name());
    }

    public static AtlasModel.Filter filter(MenuRoute route) {
        String value = route.params().get(FILTER);
        return value == null ? AtlasModel.Filter.ALL : AtlasModel.Filter.valueOf(value);
    }
}
