package art.arcane.wormholes.menu;

import java.util.UUID;

public interface MenuServiceApi extends MenuOpener {
    void receive(UUID viewerId, byte[] frame);

    void chestAction(UUID viewerId, MenuChestAction action);

    boolean chestPrecheck(UUID viewerId, String elementId, MenuInteraction interaction);

    void chestEscape(UUID viewerId);

    void chestEnd(UUID viewerId);

    void resume(UUID viewerId);

    void invalidate(MenuScope scope);

    void tick();

    void quit(UUID viewerId);

    void shutdown();

    MenuCapabilities capabilities();
}
