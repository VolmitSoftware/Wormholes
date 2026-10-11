package art.arcane.wormholes.modded.client.render.dh;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.mixin.client.DhPortalClientApiAccess;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import net.minecraft.client.multiplayer.ClientLevel;

final class DhMainRenderContext implements DhPortalRendering.Scope {
    private static DhMainRenderContext active;

    private final DhMainRenderContext parent = active;
    private final DhRenderSnapshot snapshot = new DhRenderSnapshot();
    private final IClientLevelWrapper level;
    private final DhPortalLodViews.MainPath path;
    private DhPortalLodViews.Selection selection;
    private boolean closed;

    private DhMainRenderContext(IClientLevelWrapper level, DhPortalLodViews.MainPath path) {
        this.level = level;
        this.path = path;
    }

    static DhMainRenderContext open(ClientLevel destination) {
        DhMainRenderContext scope = new DhMainRenderContext(ClientLevelWrapper.getWrapper(destination), DhPortalLodViews.mainPath(destination));
        active = scope;
        return scope;
    }

    static void prepareLods() {
        if (active != null) {
            ClientApi.RENDER_STATE.clientLevelWrapper = active.level;
        }
    }

    static void prepareRenderParams() {
        if (active == null) {
            return;
        }
        RenderParams target = DhPortalClientApiAccess.wormholes$renderParams();
        DhVec3d camera = target.exactCameraPosition;
        if (target.dhClientWorld != null && target.dhClientLevel != null && camera != null) {
            if (active.selection == null) {
                active.selection = DhPortalLodViews.acquire(active.path, target, new Vec3d(camera.x, camera.y, camera.z));
            }
            target.renderBufferHandler = active.selection.buffers();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (active != this) {
            throw new IllegalStateException("Distant Horizons main render scopes must close in order");
        }
        closed = true;
        active = parent;
        try {
            snapshot.restore();
        } finally {
            if (selection != null) {
                selection.release();
            }
        }
    }
}
