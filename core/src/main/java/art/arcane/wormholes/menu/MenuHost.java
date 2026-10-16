package art.arcane.wormholes.menu;

import java.util.Optional;
import java.util.UUID;

public interface MenuHost {
    Optional<MenuViewer> viewer(UUID viewerId);

    boolean runForViewer(UUID viewerId, Runnable task);

    MenuRenderer chestRenderer();

    MenuTransport transport();

    MenuCaptures captures();

    boolean nativeScreensEnabled();

    void warn(String message);
}
