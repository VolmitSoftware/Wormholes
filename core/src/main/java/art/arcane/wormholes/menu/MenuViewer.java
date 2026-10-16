package art.arcane.wormholes.menu;

import java.util.UUID;

public interface MenuViewer {
    UUID id();

    String name();

    boolean hasPermission(String node);

    boolean administrator();

    MenuStrings strings();
}
