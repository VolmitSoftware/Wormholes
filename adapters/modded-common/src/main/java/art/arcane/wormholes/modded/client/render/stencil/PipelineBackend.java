package art.arcane.wormholes.modded.client.render.stencil;

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

    String name();

    boolean deferred();

    void beginLayer(PortalLayer layer);

    void endLayer(PortalLayer layer);
}
