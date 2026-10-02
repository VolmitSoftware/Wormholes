package art.arcane.wormholes.modded.client.render;

final class PortalShaderWarmup {
    private boolean constructed;

    void beginFrame() {
        constructed = false;
    }

    boolean permit() {
        if (constructed) {
            return false;
        }
        constructed = true;
        return true;
    }
}
