package art.arcane.wormholes.menu.protocol;

import java.util.Objects;

public record MenuServerInfo(String wormholesVersion, String platform, boolean nativeScreens) {
    public MenuServerInfo {
        Objects.requireNonNull(wormholesVersion, "wormholesVersion");
        Objects.requireNonNull(platform, "platform");
    }
}
