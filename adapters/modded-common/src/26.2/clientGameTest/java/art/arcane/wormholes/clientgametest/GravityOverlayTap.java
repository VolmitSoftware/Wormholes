package art.arcane.wormholes.clientgametest;

public final class GravityOverlayTap {
    private static boolean blockOverlay;

    private GravityOverlayTap() {
    }

    public static void begin() {
        blockOverlay = false;
    }

    public static void submitted() {
        blockOverlay = true;
    }

    static boolean active() {
        return blockOverlay;
    }
}
