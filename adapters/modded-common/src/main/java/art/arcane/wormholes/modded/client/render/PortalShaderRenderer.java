package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.stream.EnvironmentState;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.SkyRenderer;
import java.util.List;

interface PortalShaderRenderer extends AutoCloseable {
    static PortalShaderRenderer create() {
        return PortalIrisRenderer.create();
    }

    Session acquire(int key, EnvironmentState environment, int width, int height);

    List<Resolution> resolution(Sizing sizing);

    void remove(int key);

    void discard(int key);

    void resetHistory(int key);

    boolean usesPack(Object shaderPack);

    long bytes();

    void disconnect();

    @Override
    void close();

    record Sizing(int width, int height, List<DemandView> views) {
    }

    record DemandView(int key, EnvironmentState environment, int depth) {
    }

    record Resolution(int width, int height) {
    }

    interface Frame extends AutoCloseable {
        @Override
        void close();
    }

    interface ShadowFrame extends Frame {
        CameraRenderState camera();

        boolean terrain();

        boolean translucent();

        boolean entities();

        boolean blockEntities();

        Frame features();

        void translucentDepth();
    }

    interface Session {
        boolean ready();

        boolean warm(PortalShaderContext.View view);

        TextureTarget target();

        PortalTerrainMaterials materials();

        Frame begin(PortalShaderContext.View view);

        CompiledRenderPipeline terrain(ChunkSectionLayer layer, boolean reflected);

        void endTerrain();

        ShadowFrame shadows(CameraRenderState camera);

        void prepare();

        SkyRenderer sky();

        void translucents();

        void finish();
    }
}
