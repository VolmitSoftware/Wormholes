package art.arcane.wormholes.render;

import java.util.UUID;
import java.util.function.BiPredicate;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import net.citizensnpcs.api.event.NPCSeenByPlayerEvent;
import net.citizensnpcs.api.npc.NPC;

import art.arcane.wormholes.ProjectionManager;

public final class CitizensLocalEntityOcclusionListener implements Listener {
    private final BiPredicate<UUID, UUID> localEntityOcclusion;

    public CitizensLocalEntityOcclusionListener(ProjectionManager projectionManager) {
        this(projectionManager::isLocalEntityOccluded);
    }

    CitizensLocalEntityOcclusionListener(BiPredicate<UUID, UUID> localEntityOcclusion) {
        this.localEntityOcclusion = localEntityOcclusion;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void on(NPCSeenByPlayerEvent event) {
        if (shouldCancel(event.getPlayer(), event.getNPC())) {
            event.setCancelled(true);
        }
    }

    boolean shouldCancel(Player observer, NPC npc) {
        if (observer == null || npc == null) {
            return false;
        }
        Entity entity = npc.getEntity();
        return entity != null
            && localEntityOcclusion.test(observer.getUniqueId(), entity.getUniqueId());
    }
}
