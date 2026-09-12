package art.arcane.wormholes.door;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.PocketsMessages;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces per-pocket rules inside the shared void world.
 *
 * <p>Every handler leaves the rest of the server alone: the first thing each one does is check that
 * the block or entity is inside the pocket world and maps to a pocket, so a server with no pockets
 * pays one world comparison per event.</p>
 */
public final class PocketRulesListener implements Listener {
    private final PocketLookup pockets;
    private final PocketRosterService roster;
    private final Map<UUID, Long> appliedPlayerTime;
    private final Map<UUID, UUID> occupiedSpace = new ConcurrentHashMap<>();

    public PocketRulesListener(PocketLookup pockets, PocketRosterService roster) {
        this.pockets = Objects.requireNonNull(pockets, "pockets");
        this.roster = Objects.requireNonNull(roster, "roster");
        appliedPlayerTime = new ConcurrentHashMap<>();
    }

    /**
     * The pockets somebody is standing in. Kept from the move handler, which runs on the player's own
     * region thread, so the instance sweep never has to read a foreign entity's world and position.
     */
    public Set<UUID> occupiedSpaces() {
        return Set.copyOf(occupiedSpace.values());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        PocketSpace space = spaceAt(event.getLocation());
        if (space != null && !space.rules().allowsSpawn()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = attackerOf(event.getDamager());
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        PocketSpace space = spaceAt(victim.getLocation());
        if (space == null || space.rules().allowsPvp()) {
            return;
        }
        event.setCancelled(true);
        WormholesAudience.sendMessage(attacker,
            Wormholes.text().component(attacker, PocketsMessages.DENIED_PVP));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        PocketSpace space = spaceAt(event.getEntity().getLocation());
        if (space == null || !space.rules().keepInventory()) {
            return;
        }
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    /**
     * Applies a pocket's fixed time when a player walks in and hands the sky back when they leave.
     *
     * <p>Keyed on the applied value, so a player already at the right time costs nothing per move.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Player player = event.getPlayer();
        PocketSpace space = spaceAt(to);
        if (space == null) {
            occupiedSpace.remove(player.getUniqueId());
        } else {
            occupiedSpace.put(player.getUniqueId(), space.spaceId());
        }
        long desired = space == null ? PocketRules.FOLLOW_WORLD_TIME : space.rules().fixedTime();
        Long applied = appliedPlayerTime.get(player.getUniqueId());
        if (applied == null && desired == PocketRules.FOLLOW_WORLD_TIME) {
            return;
        }
        if (applied != null && applied == desired) {
            return;
        }
        if (desired == PocketRules.FOLLOW_WORLD_TIME) {
            appliedPlayerTime.remove(player.getUniqueId());
            player.resetPlayerTime();
            return;
        }
        appliedPlayerTime.put(player.getUniqueId(), desired);
        player.setPlayerTime(desired, false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        appliedPlayerTime.remove(event.getPlayer().getUniqueId());
        occupiedSpace.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!mayBuild(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!mayBuild(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    private boolean mayBuild(Player player, Block block) {
        PocketSpace space = spaceAt(block.getLocation());
        if (space == null) {
            return true;
        }
        return space.rules().allowsBuild(roster.roleOf(space, player.getUniqueId()));
    }

    private static void deny(Player player) {
        WormholesAudience.sendMessage(player,
            Wormholes.text().component(player, PocketsMessages.DENIED_BUILD));
    }

    private static Player attackerOf(Entity damager) {
        if (damager instanceof Player direct) {
            return direct;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    private PocketSpace spaceAt(Location location) {
        if (location == null || !PocketWorldService.isPocketWorld(location.getWorld())) {
            return null;
        }
        return pockets.spaceAt(location.getBlockX(), location.getBlockZ()).orElse(null);
    }

    /** The pocket occupying a column of the shared void world, if any. */
    @FunctionalInterface
    public interface PocketLookup {
        Optional<PocketSpace> spaceAt(int blockX, int blockZ);
    }
}
