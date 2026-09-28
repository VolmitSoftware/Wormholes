package art.arcane.wormholes.network;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.portal.ILocalPortal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;

final class BukkitTraversalAdmissionPolicy {
    private BukkitTraversalAdmissionPolicy() {
    }

    static boolean acceptsEntityArrival(ILocalPortal exit, Entity created) {
        return created != null
            && exit != null
            && exit.isOpen()
            && TraversalAdmissionPolicy.acceptsInbound(exit)
            && exit.canArrive(created);
    }

    static boolean isEntityTypeDenied(EntitySnapshot snapshot) {
        NetworkConfig config = Wormholes.settings.getNetwork();
        return TraversalAdmissionPolicy.isEntityTypeDenied(snapshot.getEntityType().name(), config.entityTransferDenyTypes);
    }
}
