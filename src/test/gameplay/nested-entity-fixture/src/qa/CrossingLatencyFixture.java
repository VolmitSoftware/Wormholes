package qa;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.chunk.arrival.BukkitArrivalPreparation;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class CrossingLatencyFixture implements Listener {
    private final Map<UUID, Sample> samples = new HashMap<>();

    void execute(Player player, String[] args) {
        switch (args[1]) {
            case "arm" -> {
                Sample sample = new Sample(player.getLocation().getX());
                samples.put(player.getUniqueId(), sample);
                player.sendMessage("[whqa] LATENCY armed=true");
            }
            case "sample" -> {
                Sample sample = samples.get(player.getUniqueId());
                if (sample == null || sample.teleportNanos == 0) {
                    throw new IllegalStateException("A physical crossing and its teleport must complete before sampling");
                }
                player.sendMessage("[whqa] LATENCY delayMicros=" + (sample.crossingNanos == 0 ? -1 : (sample.teleportNanos - sample.crossingNanos) / 1000L)
                    + " earlyCrossing=" + (sample.crossingNanos == 0) + " planeZ=8.5 departureZ=" + sample.departureZ);
            }
            case "prewarm" -> {
                Settings.ARRIVAL_PREWARM_ON_INTEREST = Boolean.parseBoolean(args[2]);
                BukkitArrivalPreparation.disconnected(player.getUniqueId());
                player.sendMessage("[whqa] LATENCY prewarm=" + Settings.ARRIVAL_PREWARM_ON_INTEREST);
            }
            case "cooldown" -> {
                Settings.TELEPORT_COOLDOWN_MILLIS = Long.parseLong(args[2]);
                player.sendMessage("[whqa] LATENCY cooldownMillis=" + Settings.TELEPORT_COOLDOWN_MILLIS);
            }
            case "clientview" -> {
                boolean enabled = Boolean.parseBoolean(args[2]);
                Wormholes.projectionManager.clientView().runtimeEnabled(enabled);
                player.sendMessage("[whqa] LATENCY clientview=" + enabled);
            }
            default -> throw new IllegalArgumentException("Unknown crossing latency operation");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) {
            return;
        }
        Sample sample = samples.get(event.getPlayer().getUniqueId());
        Location from = event.getFrom();
        Location to = event.getTo();
        if (sample == null || sample.crossingNanos != 0 || to == null || from.getWorld() != to.getWorld()
            || Math.abs(from.getX() - sample.sourceX) > 3) {
            return;
        }
        if ((from.getZ() - 8.5D) * (to.getZ() - 8.5D) <= 0 && from.getZ() != to.getZ()) {
            sample.crossingNanos = System.nanoTime();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        Sample sample = samples.get(event.getPlayer().getUniqueId());
        if (sample == null || sample.teleportNanos != 0 || event.getTo() == null
            || Math.abs(event.getTo().getX() - sample.sourceX) < 50) {
            return;
        }
        sample.teleportNanos = System.nanoTime();
        sample.departureZ = event.getFrom().getZ();
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        samples.remove(event.getPlayer().getUniqueId());
    }

    private static final class Sample {
        private final double sourceX;
        private long crossingNanos;
        private long teleportNanos;
        private double departureZ;

        private Sample(double sourceX) {
            this.sourceX = sourceX;
        }
    }
}
