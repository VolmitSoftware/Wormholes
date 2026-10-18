package qa;

import art.arcane.optics.math.CellKeys;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.chunk.arrival.BukkitArrivalPreparation;
import art.arcane.wormholes.chunk.presend.BukkitChunkPreSendProvider;
import art.arcane.wormholes.chunk.presend.BukkitChunkPreSendTransaction;
import art.arcane.wormholes.chunk.presend.ChunkPreSendSettings;
import art.arcane.wormholes.platform.WormholesPlatform;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

final class ArrivalFixture implements Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Set<Long>> stableChunks = new HashMap<>();

    ArrivalFixture(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void execute(Player player, String[] args) {
        if (plugin.getServer().getName().contains("Folia")) {
            throw new IllegalStateException("Arrival fixture requires a single-region test server");
        }
        switch (args[1]) {
            case "setup" -> {
                player.setGameMode(GameMode.CREATIVE);
                floor(target("return"));
                floor(target("same"));
                floor(target("end"));
                player.sendMessage("[whqa] ARRIVAL setup=true");
            }
            case "enabled" -> {
                boolean enabled = Boolean.parseBoolean(args[2]);
                ChunkPreSendSettings.apply(enabled, 3, 32, 2000);
                player.sendMessage("[whqa] ARRIVAL enabled=" + enabled);
            }
            case "stage" -> {
                cancel(player.getUniqueId());
                teleport(player, target("return"), null);
            }
            case "prepare" -> {
                cancel(player.getUniqueId());
                Location target = target(args[2]);
                BukkitArrivalPreparation.prepare(player, target);
                pending.put(player.getUniqueId(), new Pending(target,
                    BukkitChunkPreSendProvider.preSend(player, target), null));
                player.sendMessage("[whqa] ARRIVAL prepared=" + args[2]);
            }
            case "cross" -> {
                Pending entry = Objects.requireNonNull(pending.remove(player.getUniqueId()), "No prepared arrival");
                teleport(player, entry.target(), entry);
            }
            case "dirty" -> {
                Pending entry = Objects.requireNonNull(pending.get(player.getUniqueId()), "No prepared arrival");
                Location target = entry.target();
                pending.put(player.getUniqueId(), new Pending(target, entry.chunks(),
                    BukkitArrivalPreparation.claimForTeleport(player, target)));
                target.getWorld().getBlockAt(target.getBlockX() + 2, 199, target.getBlockZ()).setType(Material.GOLD_BLOCK, false);
                Wormholes.projectionChangeTracker.markChanged(target.getWorld().getUID(), target.getBlockX() + 2, target.getBlockZ());
                player.sendMessage("[whqa] ARRIVAL dirty=true");
            }
            case "cancel" -> {
                cancel(player.getUniqueId());
                player.sendMessage("[whqa] ARRIVAL cancelled=true");
            }
            default -> throw new IllegalArgumentException("Unknown arrival fixture command");
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }

    void close() {
        for (UUID playerId : pending.keySet().toArray(UUID[]::new)) {
            cancel(playerId);
        }
        for (World world : plugin.getServer().getWorlds()) {
            world.removePluginChunkTickets(plugin);
        }
        stableChunks.clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void flow(BlockFromToEvent event) {
        if (stable(event.getBlock()) || stable(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void fluid(FluidLevelChangeEvent event) {
        if (stable(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    private boolean stable(Block block) {
        Set<Long> chunks = stableChunks.get(block.getWorld().getUID());
        return chunks != null && chunks.contains(CellKeys.chunkKey(block.getX() >> 4, block.getZ() >> 4));
    }

    private Location target(String mode) {
        World world = Objects.requireNonNull(plugin.getServer().getWorld(mode.equals("end") ? "world_the_end" : "world"));
        if (!mode.equals("end") && !mode.equals("same") && !mode.equals("return")) {
            throw new IllegalArgumentException("Expected same, end or return");
        }
        return new Location(world, mode.equals("return") ? 8.5D : 520.5D, 200D, 8.5D, 0F, 90F);
    }

    private void floor(Location center) {
        World world = center.getWorld();
        int centerX = center.getBlockX();
        int centerZ = center.getBlockZ();
        Set<Long> stable = stableChunks.computeIfAbsent(world.getUID(), ignored -> new HashSet<>());
        for (int x = (centerX >> 4) - 2; x <= (centerX >> 4) + 2; x++) {
            for (int z = (centerZ >> 4) - 2; z <= (centerZ >> 4) + 2; z++) {
                stable.add(CellKeys.chunkKey(x, z));
            }
        }
        for (int x = (centerX >> 4) - 1; x <= (centerX >> 4) + 1; x++) {
            for (int z = (centerZ >> 4) - 1; z <= (centerZ >> 4) + 1; z++) {
                world.addPluginChunkTicket(x, z, plugin);
            }
        }
        for (int x = centerX - 12; x <= centerX + 12; x++) {
            for (int z = centerZ - 12; z <= centerZ + 12; z++) {
                world.getBlockAt(x, 199, z).setType(((x + z) & 1) == 0 ? Material.RED_CONCRETE : Material.SEA_LANTERN, false);
                for (int y = 200; y <= 205; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
        world.setTime(6000);
        world.setStorm(false);
        for (String rule : new String[]{"advance_time", "advance_weather", "spawn_mobs"}) {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
                "execute in " + world.getKey() + " run gamerule minecraft:" + rule + " false");
        }
        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),
            "execute in " + world.getKey() + " run gamerule minecraft:random_tick_speed 0");
    }

    private void teleport(Player player, Location target, Pending prepared) {
        if (prepared == null) {
            teleportReady(player, target, null, BukkitArrivalPreparation.claimForTeleport(player, target));
            return;
        }
        if (prepared.staleAttempt() != null) {
            teleportReady(player, target, prepared, prepared.staleAttempt());
            return;
        }
        BukkitArrivalPreparation.prepareForTeleport(player, target,
            attempt -> teleportReady(player, target, prepared, attempt), () -> {
                if (prepared.chunks() != null) {
                    prepared.chunks().cancel();
                }
                WormholesPlatform.scheduleEntity(plugin, player,
                    () -> player.sendMessage("[whqa] ARRIVAL crossed=false"), () -> {}, 0);
            });
    }

    private void teleportReady(Player player, Location target, Pending prepared, BukkitArrivalPreparation.Attempt attempt) {
        attempt.beforeTeleport();
        WormholesPlatform.teleport(plugin, player, target, PlayerTeleportEvent.TeleportCause.PLUGIN)
            .whenComplete((success, failure) -> {
                if (failure != null || !Boolean.TRUE.equals(success)) {
                    attempt.cancel();
                    if (prepared != null && prepared.chunks() != null) {
                        prepared.chunks().cancel();
                    }
                    if (failure != null) {
                        plugin.getLogger().log(Level.SEVERE, "Arrival fixture teleport failed", failure);
                    }
                } else {
                    attempt.complete();
                    if (prepared != null && prepared.chunks() != null) {
                        prepared.chunks().commit();
                    }
                }
                WormholesPlatform.scheduleEntity(plugin, player,
                    () -> player.sendMessage("[whqa] ARRIVAL crossed=" + Boolean.TRUE.equals(success)), () -> {}, 0);
            });
    }

    private void cancel(UUID playerId) {
        Pending previous = pending.remove(playerId);
        Player player = plugin.getServer().getPlayer(playerId);
        if (previous != null && player != null) {
            BukkitArrivalPreparation.claimForTeleport(player, previous.target()).cancel();
        }
        if (previous != null && previous.chunks() != null) {
            previous.chunks().cancel();
        }
    }

    private record Pending(Location target, BukkitChunkPreSendTransaction chunks, BukkitArrivalPreparation.Attempt staleAttempt) {}
}
