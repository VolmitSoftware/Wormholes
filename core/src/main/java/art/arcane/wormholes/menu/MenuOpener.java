package art.arcane.wormholes.menu;

import java.util.UUID;

public interface MenuOpener {
    void open(UUID viewerId, MenuRoute route);
}
