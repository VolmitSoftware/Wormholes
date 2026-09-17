package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.event.ProtectionProbe;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.NexusMessages;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalAccessPolicy;
import art.arcane.wormholes.portal.PortalInteractionGestures;
import art.arcane.wormholes.service.WormholesHud;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.UUID;

/**
 * In-world dialing for players who do not manage the portal. Scrolling while sneaking inside the
 * capture zone steps through addresses; sneak plus an empty hand on the frame opens the dial menu.
 * Managers keep the ordinary portal menu, so both gestures bail out when the clicker may manage it.
 * A gesture repoints a portal for everybody, so it runs the same admission travel runs and refuses a
 * network the player cannot see.
 */
public final class DialGestures implements Listener {
    private final NetworkRegistry registry;
    private final Dialer dialer;
    private final DialMenu dialMenu;

    public DialGestures(NetworkRegistry registry, Dialer dialer, DialMenu dialMenu) {
        this.registry = registry;
        this.dialer = dialer;
        this.dialMenu = dialMenu;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }
        LocalPortal portal = networkedPortalAt(player, player.getLocation());
        if (portal == null || canManage(player, portal)) {
            return;
        }
        event.setCancelled(true);
        int delta = scrollDirection(event.getPreviousSlot(), event.getNewSlot());
        announce(player, portal, dialer.next(portal, delta, player.getUniqueId(), System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on(PlayerInteractEvent event) {
        if (ProtectionProbe.isProbe(event)) {
            return;
        }
        Player player = event.getPlayer();
        if (!PortalInteractionGestures.opensPortalMenu(player.isSneaking(),
                player.getInventory().getItemInMainHand().getType().isAir(), event.getAction(), event.getHand())) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        LocalPortal portal = networkedPortalAt(player, clicked.getLocation().add(0.5D, 0.5D, 0.5D));
        if (portal == null || canManage(player, portal)) {
            return;
        }
        event.setCancelled(true);
        dialMenu.open(portal, player);
    }

    /** A hotbar step of one in either direction, wrapping across the ends of the bar. */
    static int scrollDirection(int previousSlot, int newSlot) {
        int forward = Math.floorMod(newSlot - previousSlot, 9);
        return forward <= 4 ? 1 : -1;
    }

    private LocalPortal networkedPortalAt(Player player, Location location) {
        if (Wormholes.portalManager == null || location.getWorld() == null) {
            return null;
        }
        for (ILocalPortal candidate : Wormholes.portalManager.getLocalPortals()) {
            if (!(candidate instanceof LocalPortal portal) || portal.isDestroyed()) {
                continue;
            }
            if (portal.getStructure() == null || !location.getWorld().equals(portal.getStructure().getWorld())) {
                continue;
            }
            if (!portal.getStructure().getCaptureZone().containsPrimitive(
                    location.getX(), location.getY(), location.getZ())) {
                continue;
            }
            NexusPortalExtension state = portal.extension(NexusPortalExtension.class);
            if (state == null || state.networkId() == null) {
                continue;
            }
            if (DialAdmission.allows(portal, registry.byId(state.networkId()), player)) {
                return portal;
            }
        }
        return null;
    }

    private static boolean canManage(Player player, LocalPortal portal) {
        boolean administrator = player.isOp() || player.hasPermission("wormholes.admin");
        UUID playerId = player.getUniqueId();
        return PortalAccessPolicy.canManage(portal.getId(), portal.getOwner(), playerId, administrator);
    }

    private void announce(Player player, LocalPortal portal, Dialer.DialResult result) {
        if (result != Dialer.DialResult.DIALED) {
            return;
        }
        NexusPortalExtension state = portal.extension(NexusPortalExtension.class);
        NetworkMember member = dialer.currentTarget(portal);
        WormholesHud.notice(player, Wormholes.text().component(player, NexusMessages.DIALED, NexusText.args(
                "portal", portal.getName(),
                "address", state.dial().currentAddress(),
                "destination", member == null || member.label().isEmpty()
                        ? state.dial().currentAddress() : member.label())));
    }
}
