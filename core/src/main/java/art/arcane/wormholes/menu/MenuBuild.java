package art.arcane.wormholes.menu;

import java.util.Objects;

public record MenuBuild(MenuScreen screen, ChestLayout chest, MenuBindings bindings) {
    public MenuBuild {
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(chest, "chest");
        Objects.requireNonNull(bindings, "bindings");
    }

    public MenuBuild withScreen(MenuScreen next) {
        return new MenuBuild(next, chest, bindings);
    }

    public String windowKey() {
        return chest.windowKey() == null ? screen.screenId() : chest.windowKey();
    }
}
