package art.arcane.wormholes.clientgametest;

import java.util.ArrayList;
import java.util.List;

public final class SystemNotices {
    private static final List<String> NOTICES = new ArrayList<>();

    private SystemNotices() {
    }

    public static synchronized void record(String notice) {
        NOTICES.add(notice);
    }

    static synchronized int mark() {
        return NOTICES.size();
    }

    static synchronized List<String> since(int mark) {
        return List.copyOf(NOTICES.subList(Math.min(mark, NOTICES.size()), NOTICES.size()));
    }
}
