package art.arcane.wormholes.modded.client.render.stencil;

import org.joml.Matrix4fc;

/** Shader pipeline integration for portal layers: none for vanilla, Iris/Oculus when a pack is active. */
public interface PipelineBackend {
    PipelineBackend NONE = new PipelineBackend() {
        @Override
        public String name() {
            return "vanilla";
        }

        @Override
        public boolean deferred() {
            return false;
        }

        @Override
        public void beginLayer(PortalLayer layer) {
        }

        @Override
        public void endLayer(PortalLayer layer) {
        }
    };

    /** Short name for debug output. */
    String name();

    /** Whether layers are captured and composited after the pipeline's own passes instead of drawn inline. */
    boolean deferred();

    /** Called before a layer re-enters the level renderer. */
    void beginLayer(PortalLayer layer);

    /** Called after a layer's level render returns. */
    void endLayer(PortalLayer layer);

    /** Called once per frame with the main camera, before any view is prepared. */
    default void prepareViews(FrameCamera camera, Matrix4fc projection) {
    }

    /** Whether the view may render this frame. */
    default boolean prepareView(PortalView view) {
        return true;
    }

    /** Releases per-view pipeline state when the view closes. */
    default void closeView(PortalView view) {
    }
}
