package art.arcane.wormholes.menu;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record MenuBinding(MenuGuard guard, Map<MenuInteraction, MenuHandler> handlers, MenuConfirmFactory confirmation) {
    public MenuBinding {
        Objects.requireNonNull(guard, "guard");
        handlers = Map.copyOf(Objects.requireNonNull(handlers, "handlers"));
    }

    public Optional<MenuHandler> handler(MenuInteraction interaction) {
        return Optional.ofNullable(handlers.get(interaction));
    }
}
