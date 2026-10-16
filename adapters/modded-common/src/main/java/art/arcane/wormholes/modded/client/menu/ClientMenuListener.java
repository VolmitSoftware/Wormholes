package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.MenuFeedback;
import art.arcane.wormholes.menu.MenuScreen;

public interface ClientMenuListener {
    void opened(MenuScreen screen);

    void updated(MenuScreen screen);

    void closed();

    void feedback(MenuFeedback feedback);
}
