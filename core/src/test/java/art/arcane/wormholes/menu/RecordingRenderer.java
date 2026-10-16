package art.arcane.wormholes.menu;

import java.util.ArrayList;
import java.util.List;

import art.arcane.wormholes.menu.protocol.MenuProtocolException;

public final class RecordingRenderer implements MenuRenderer {
    public final List<String> events = new ArrayList<>();
    public final List<MenuBuild> builds = new ArrayList<>();
    public final List<MenuNotice> notices = new ArrayList<>();

    @Override
    public void show(MenuViewer viewer, MenuBuild build, boolean opening) throws MenuProtocolException {
        events.add((opening ? "open:" : "update:") + build.screen().screenId() + "@" + build.screen().revision());
        builds.add(build);
    }

    @Override
    public void close(MenuViewer viewer) {
        events.add("close");
    }

    @Override
    public void notice(MenuViewer viewer, MenuNotice notice) {
        events.add("notice:" + notice.chestChannel());
        notices.add(notice);
    }

    public MenuBuild last() {
        return builds.get(builds.size() - 1);
    }

    public MenuScreen screen() {
        return last().screen();
    }

    public String lastEvent() {
        return events.get(events.size() - 1);
    }

    public String lastNotice() {
        return notices.get(notices.size() - 1).feedback().text();
    }
}
