package art.arcane.wormholes.modded.client.render.dh;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.mixin.client.DhPortalClientApiAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4fc;
import org.joml.Vector4dc;

public final class DhPortalRendering {
    private static final boolean PRESENT = DhPortalRendering.class.getClassLoader()
        .getResource("com/seibel/distanthorizons/core/api/internal/ClientApi.class") != null;
    private static final Scope NONE = () -> {};

    private DhPortalRendering() {
    }

    public static Scope openMain(ClientLevel destination) {
        return PRESENT ? DhMainRenderContext.open(destination) : NONE;
    }

    public static Scope open(PortalView view, ClientLevel destination, Matrix4fc modelView, Matrix4fc projection, float partialTicks,
                             Vector4dc worldPlane, Vec3d camera) {
        return PRESENT ? DhPortalRenderContext.open(view, destination, modelView, projection, partialTicks, worldPlane, camera) : NONE;
    }

    public static void prepareLods() {
        if (PRESENT) {
            DhPortalRenderContext.prepareLods();
        }
    }

    public static void prepareRenderParams() {
        if (PRESENT) {
            DhPortalLevelWrappers.bind(DhPortalClientApiAccess.wormholes$renderParams());
            DhPortalRenderContext.prepareRenderParams();
        }
    }

    public static void tickViews() {
        if (PRESENT) {
            DhPortalLodViews.tick();
        }
    }

    public static void closeView(PortalView view) {
        if (PRESENT) {
            DhPortalLodViews.closeView(view);
        }
    }

    public static void clearViews() {
        if (PRESENT) {
            DhPortalLodViews.clear();
        }
    }

    public static void closeProvider(Object provider) {
        if (PRESENT) {
            DhPortalLodViews.closeProvider(provider);
        }
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
