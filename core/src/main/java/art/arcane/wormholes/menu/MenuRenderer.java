package art.arcane.wormholes.menu;

import art.arcane.wormholes.menu.protocol.MenuProtocolException;

public interface MenuRenderer {
    void show(MenuViewer viewer, MenuBuild build, boolean opening) throws MenuProtocolException;

    void close(MenuViewer viewer);

    void notice(MenuViewer viewer, MenuNotice notice);
}
