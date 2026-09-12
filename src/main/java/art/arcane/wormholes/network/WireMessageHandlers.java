package art.arcane.wormholes.network;

import java.util.List;
import java.util.Map;

/** Published table of lane-registered {@link WireMessageHandler}s, consulted before the legacy router sink. */
public final class WireMessageHandlers {
    private static volatile Map<WireMessageType, List<WireMessageHandler>> handlers = Map.of();

    private WireMessageHandlers() {
    }

    public static void install(Map<WireMessageType, List<WireMessageHandler>> table) {
        handlers = table == null ? Map.of() : table;
    }

    public static void clear() {
        handlers = Map.of();
    }

    static boolean dispatch(String peerName, WireMessage message) {
        List<WireMessageHandler> list = handlers.get(message.type());
        if (list == null || list.isEmpty()) {
            return false;
        }
        boolean consumed = false;
        for (WireMessageHandler handler : list) {
            if (handler.handle(peerName, message)) {
                consumed = true;
            }
        }
        return consumed;
    }
}
