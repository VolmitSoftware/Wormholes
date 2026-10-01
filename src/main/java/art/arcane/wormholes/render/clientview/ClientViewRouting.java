package art.arcane.wormholes.render.clientview;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.rtp.RtpRimRenderer;
import art.arcane.wormholes.render.PortalProjector;

public interface ClientViewRouting {
    boolean holdsVanilla(Player observer, long frameTick);

    void route(Player observer, Location eye, List<ILocalPortal> interested, List<ILocalPortal> projectable,
               Map<UUID, PortalProjector.RtpProjectionTarget> rtpTargets, long frameTick);

    default boolean receiver(Player observer) {
        return false;
    }

    default void rim(Player observer, UUID portal, RtpRimRenderer.Sample sample) {
    }

    static ClientViewRouting none() {
        return new ClientViewRouting() {
            @Override
            public boolean holdsVanilla(Player observer, long frameTick) {
                return false;
            }

            @Override
            public void route(Player observer, Location eye, List<ILocalPortal> interested, List<ILocalPortal> projectable,
                              Map<UUID, PortalProjector.RtpProjectionTarget> rtpTargets, long frameTick) {
            }
        };
    }
}
