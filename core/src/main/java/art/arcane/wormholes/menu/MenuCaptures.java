package art.arcane.wormholes.menu;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface MenuCaptures {
    void prompt(MenuViewer viewer, List<String> promptLines, Consumer<String> submitted, Runnable cancelled);

    void dropItem(MenuViewer viewer, List<String> promptLines, Consumer<CapturedItem> captured, Runnable cancelled);

    Optional<CapturedItem> heldItem(MenuViewer viewer);
}
