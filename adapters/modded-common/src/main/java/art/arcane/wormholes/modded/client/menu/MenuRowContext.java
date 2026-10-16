package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.MenuChrome;
import net.minecraft.client.gui.Font;

import java.util.Objects;
import java.util.function.Consumer;

public record MenuRowContext(Font font, MenuChrome chrome, Consumer<MenuRowAction> actions, Consumer<CycleElement> picker) {
    public MenuRowContext {
        Objects.requireNonNull(font, "font");
        Objects.requireNonNull(chrome, "chrome");
        Objects.requireNonNull(actions, "actions");
        Objects.requireNonNull(picker, "picker");
    }
}
