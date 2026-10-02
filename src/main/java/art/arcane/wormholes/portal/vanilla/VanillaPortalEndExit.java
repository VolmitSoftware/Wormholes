package art.arcane.wormholes.portal.vanilla;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.DragonBattle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

final class VanillaPortalEndExit {
    private final Set<UUID> scanning = ConcurrentHashMap.newKeySet();

    void discover() {
        if (!Settings.REPLACE_NETHER_AND_END_PORTALS || Wormholes.portalManager == null) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.THE_END || WorldGroups.isDisabled(world)) {
                continue;
            }
            DragonBattle battle = world.getEnderDragonBattle();
            Location center = battle == null ? null : battle.getEndPortalLocation();
            if (center != null && scanning.add(world.getUID())) {
                scan(world, center);
            }
        }
    }

    private void scan(World world, Location center) {
        List<CompletableFuture<Set<Block>>> snapshots = new ArrayList<>(4);
        for (int chunkX = (center.getBlockX() - 2) >> 4; chunkX <= (center.getBlockX() + 2) >> 4; chunkX++) {
            for (int chunkZ = (center.getBlockZ() - 2) >> 4; chunkZ <= (center.getBlockZ() + 2) >> 4; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    scanning.remove(world.getUID());
                    return;
                }
            }
        }
        for (int chunkX = (center.getBlockX() - 2) >> 4; chunkX <= (center.getBlockX() + 2) >> 4; chunkX++) {
            for (int chunkZ = (center.getBlockZ() - 2) >> 4; chunkZ <= (center.getBlockZ() + 2) >> 4; chunkZ++) {
                CompletableFuture<Set<Block>> snapshot = new CompletableFuture<>();
                snapshots.add(snapshot);
                int x = chunkX;
                int z = chunkZ;
                if (!FoliaScheduler.runRegion(Wormholes.instance, world, x, z, () -> capture(world, center, x, z, snapshot))) {
                    snapshot.completeExceptionally(new IllegalStateException("End fountain owning region refused its aperture scan"));
                }
            }
        }
        CompletableFuture.allOf(snapshots.toArray(CompletableFuture<?>[]::new)).whenComplete((ignored, failure) -> {
            try {
                if (failure != null) {
                    Wormholes.instance.getLogger().log(Level.WARNING, "End fountain aperture scan failed in " + world.getName(), failure);
                    return;
                }
                Set<Block> cells = new HashSet<>();
                for (CompletableFuture<Set<Block>> snapshot : snapshots) {
                    cells.addAll(snapshot.join());
                }
                reconcile(world, cells);
            } catch (RuntimeException failureDuringRegistration) {
                Wormholes.instance.getLogger().log(Level.WARNING, "Could not register End fountain in " + world.getName(), failureDuringRegistration);
            } finally {
                scanning.remove(world.getUID());
            }
        });
    }

    private static void capture(World world, Location center, int chunkX, int chunkZ, CompletableFuture<Set<Block>> snapshot) {
        try {
            Set<Block> cells = new HashSet<>();
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                for (int x = Math.max(center.getBlockX() - 2, chunkX << 4); x <= Math.min(center.getBlockX() + 2, (chunkX << 4) + 15); x++) {
                    for (int z = Math.max(center.getBlockZ() - 2, chunkZ << 4); z <= Math.min(center.getBlockZ() + 2, (chunkZ << 4) + 15); z++) {
                        Block block = world.getBlockAt(x, center.getBlockY(), z);
                        if (block.getType() == Material.END_PORTAL) {
                            cells.add(block);
                        }
                    }
                }
            } else {
                throw new IllegalStateException("End fountain chunk unloaded before its aperture scan");
            }
            snapshot.complete(Set.copyOf(cells));
        } catch (RuntimeException failure) {
            snapshot.completeExceptionally(failure);
        }
    }

    private static void reconcile(World world, Set<Block> cells) {
        if (!Settings.REPLACE_NETHER_AND_END_PORTALS || Wormholes.portalManager == null) {
            return;
        }
        ILocalPortal existing = null;
        for (ILocalPortal portal : Wormholes.portalManager.getLocalPortals()) {
            if (portal.getDimensionalPortalKind() == DimensionalPortalKind.END_EXIT && world.equals(portal.getWorld())) {
                existing = portal;
                break;
            }
        }
        if (existing != null && sameCells(existing, cells)) {
            return;
        }
        if (existing != null) {
            existing.destroy();
        }
        if (!cells.isEmpty()) {
            PortalFactory.createFromCells(cells, PortalFrame.canonical(Direction.U), PortalType.PORTAL,
                "End return", DimensionalPortalKind.END_EXIT);
        }
    }

    static boolean sameCells(ILocalPortal portal, Set<Block> cells) {
        if (portal.getStructure() == null || portal.getStructure().getBlockPositions().size() != cells.size()) {
            return false;
        }
        for (Block cell : cells) {
            if (!portal.getStructure().containsBlock(cell.getX(), cell.getY(), cell.getZ())) {
                return false;
            }
        }
        return true;
    }
}
