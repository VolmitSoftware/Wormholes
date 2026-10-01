package art.arcane.wormholes.modded.client;

public final class ClientLightGate {
    private static volatile Object level;
    private static volatile Object engine;
    private static int depth;

    private ClientLightGate() {
    }

    public static void enter(Object levelToken, Object lightEngine) {
        if (depth == 0) {
            level = levelToken;
            engine = lightEngine;
        }
        depth++;
    }

    public static void exit() {
        if (depth == 0) {
            return;
        }
        depth--;
        if (depth == 0) {
            level = null;
            engine = null;
        }
    }

    public static boolean suppresses(Object lightEngine) {
        Object active = engine;
        return active != null && active == lightEngine;
    }

    public static boolean suppressesLevel(Object levelToken) {
        Object active = level;
        return active != null && active == levelToken;
    }

    public static int depth() {
        return depth;
    }
}
