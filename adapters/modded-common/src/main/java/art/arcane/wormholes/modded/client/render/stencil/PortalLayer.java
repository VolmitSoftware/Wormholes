package art.arcane.wormholes.modded.client.render.stencil;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4dc;
import org.joml.Vector4fc;

public final class PortalLayer {
    private final int depth;
    private final PortalView view;
    private final ClientLevel level;
    private final LevelRenderer renderer;
    private final boolean shared;
    private final CameraRenderState camera;
    private final Vector4dc worldClipPlane;
    private final Vector4fc clipSpacePlane;
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> nearbySections;

    PortalLayer(int depth, PortalView view, ClientLevel level, LevelRenderer renderer, boolean shared, CameraRenderState camera, Vector4dc worldClipPlane,
                Vector4fc clipSpacePlane, ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
                ObjectArrayList<SectionRenderDispatcher.RenderSection> nearbySections) {
        this.depth = depth;
        this.view = view;
        this.level = level;
        this.renderer = renderer;
        this.shared = shared;
        this.camera = camera;
        this.worldClipPlane = worldClipPlane;
        this.clipSpacePlane = clipSpacePlane;
        this.visibleSections = visibleSections;
        this.nearbySections = nearbySections;
    }

    public int depth() {
        return depth;
    }

    public PortalView view() {
        return view;
    }

    public ClientLevel level() {
        return level;
    }

    public LevelRenderer renderer() {
        return renderer;
    }

    public boolean shared() {
        return shared;
    }

    public CameraRenderState camera() {
        return camera;
    }

    public Vector4dc worldClipPlane() {
        return worldClipPlane;
    }

    public Vector4fc clipSpacePlane() {
        return clipSpacePlane;
    }

    public ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections() {
        return visibleSections;
    }

    public ObjectArrayList<SectionRenderDispatcher.RenderSection> nearbySections() {
        return nearbySections;
    }
}
