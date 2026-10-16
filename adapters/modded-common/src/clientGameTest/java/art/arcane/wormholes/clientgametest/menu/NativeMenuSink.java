package art.arcane.wormholes.clientgametest.menu;

import art.arcane.wormholes.menu.protocol.MenuClientMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class NativeMenuSink {
    private static final List<MenuClientMessage> MESSAGES = new CopyOnWriteArrayList<>();

    private NativeMenuSink() {
    }

    public static void record(MenuClientMessage message) {
        MESSAGES.add(message);
    }

    public static List<MenuClientMessage> messages() {
        return List.copyOf(MESSAGES);
    }

    public static void clear() {
        MESSAGES.clear();
    }
}
