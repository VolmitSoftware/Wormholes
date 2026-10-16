package art.arcane.wormholes.menu;

import java.util.Objects;

public record MenuContext(MenuViewer viewer, MenuRoute route, int page, MenuState state, CapturedItem capturedItem,
                          MenuActionOrigin origin) {
    public MenuContext {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(origin, "origin");
        if (page < 0) {
            throw new IllegalArgumentException("page " + page + " must not be negative");
        }
    }

    public MenuStrings strings() {
        return viewer.strings();
    }
}
