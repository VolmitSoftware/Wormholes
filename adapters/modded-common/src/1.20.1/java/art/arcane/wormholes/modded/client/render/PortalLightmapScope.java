package art.arcane.wormholes.modded.client.render;

/**
 * The lightmap texture of the streamed view being drawn, so draws that bind the game's lightmap (entities, block
 * entities) can bind the view's instead. {@link #current()} is 0 outside a view.
 */
public final class PortalLightmapScope implements AutoCloseable {
    private static int current;
    private final int previous;

    PortalLightmapScope(int lightmap) {
        previous = current;
        current = lightmap;
    }

    /** The GL texture id of the active view's lightmap, or 0. Render thread only. */
    public static int current() {
        return current;
    }

    @Override
    public void close() {
        current = previous;
    }
}
