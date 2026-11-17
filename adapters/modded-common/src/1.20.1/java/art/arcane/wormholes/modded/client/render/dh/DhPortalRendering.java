package art.arcane.wormholes.modded.client.render.dh;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4fc;
import org.joml.Vector4dc;

/**
 * Distant Horizons 3.3 on 1.20.1 draws LODs with raw OpenGL into one level at a time and has no per-view LOD buffers,
 * so portals never show LODs: {@link DhLodPasses} cancels every DH pass inside a portal layer and keeps the main view's
 * later passes out of the apertures. The scopes here have nothing to switch and are inert.
 */
public final class DhPortalRendering {
    private static final Scope NONE = () -> {
    };

    private DhPortalRendering() {
    }

    public static Scope openMain(ClientLevel destination) {
        return NONE;
    }

    public static Scope open(PortalView view, ClientLevel destination, Matrix4fc modelView, Matrix4fc projection, float partialTicks,
                             Vector4dc worldPlane, Vec3d camera) {
        return NONE;
    }

    public static void closeView(PortalView view) {
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
