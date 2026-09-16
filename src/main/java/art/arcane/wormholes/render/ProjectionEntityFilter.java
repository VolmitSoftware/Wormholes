package art.arcane.wormholes.render;

import art.arcane.wormholes.EffectManager;
import org.bukkit.entity.Entity;

public final class ProjectionEntityFilter {
    private ProjectionEntityFilter() {
    }

    public static boolean canCapture(Entity entity) {
        return entity != null && !entity.isDead() && entity.isValid()
            && !EffectManager.isPortalEffectEntity(entity);
    }

    public static boolean canBroadcast(Entity entity) {
        return canCapture(entity) && entity.isVisibleByDefault();
    }
}
