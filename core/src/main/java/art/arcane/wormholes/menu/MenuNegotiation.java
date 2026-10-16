package art.arcane.wormholes.menu;

import java.util.Optional;

import art.arcane.wormholes.menu.protocol.MenuProtocol;

public final class MenuNegotiation {
    private MenuNegotiation() {
    }

    public static Optional<MenuCapability> accept(int clientVersion) {
        if (clientVersion < 1) {
            return Optional.empty();
        }
        return Optional.of(new MenuCapability(Math.min(clientVersion, MenuProtocol.VERSION)));
    }
}
