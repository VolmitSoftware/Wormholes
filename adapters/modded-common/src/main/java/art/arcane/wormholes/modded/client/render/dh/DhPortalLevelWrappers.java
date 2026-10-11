package art.arcane.wormholes.modded.client.render.dh;

import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;

public final class DhPortalLevelWrappers {
    private DhPortalLevelWrappers() {
    }

    static void bind(RenderParams parameters) {
        if (parameters.dhClientLevel instanceof Binding level && parameters.clientLevelWrapper != null) {
            level.wormholes$clientLevelWrapper(parameters.clientLevelWrapper);
        }
    }

    public interface Binding {
        void wormholes$clientLevelWrapper(IClientLevelWrapper wrapper);
    }
}
