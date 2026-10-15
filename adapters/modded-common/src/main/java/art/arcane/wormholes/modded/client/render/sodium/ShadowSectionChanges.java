package art.arcane.wormholes.modded.client.render.sodium;

public final class ShadowSectionChanges {
    private boolean pending;

    public void sectionChanged(boolean shadowPass) {
        if (shadowPass) {
            pending = true;
        }
    }

    public boolean claimForGraph(boolean shadowPass) {
        if (!pending || shadowPass) {
            return false;
        }
        pending = false;
        return true;
    }
}
