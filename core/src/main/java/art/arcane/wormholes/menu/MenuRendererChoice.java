package art.arcane.wormholes.menu;

import java.util.Optional;

public enum MenuRendererChoice {
    NATIVE,
    CHEST;

    public static MenuRendererChoice choose(Optional<MenuCapability> capability, boolean nativeEnabled) {
        if (!nativeEnabled || capability.isEmpty() || capability.get().version() < 1) {
            return CHEST;
        }
        return NATIVE;
    }
}
