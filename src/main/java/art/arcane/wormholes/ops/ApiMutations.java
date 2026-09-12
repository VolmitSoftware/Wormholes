package art.arcane.wormholes.ops;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.api.portal.PortalMutations;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.vanilla.PortalFactory;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Runs API mutations on the region thread that owns the portal and completes the caller's future. */
public final class ApiMutations implements PortalMutations {
    @Override
    public CompletableFuture<UUID> create(World world, Set<Block> cells, String name, UUID owner) {
        if (world == null || cells == null || cells.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Block first = cells.iterator().next();
        return onRegion(first.getLocation(), () -> {
            ILocalPortal created = PortalFactory.createFromCells(cells,
                PortalFrame.canonical(normalOf(cells)), PortalType.PORTAL, name);
            if (created == null) {
                return null;
            }
            if (owner != null && created instanceof LocalPortal local) {
                local.setOwner(owner);
                local.save();
            }
            return created.getId();
        });
    }

    @Override
    public CompletableFuture<Boolean> link(UUID source, UUID destination) {
        return onPortal(source, portal -> {
            ILocalPortal target = portal(destination);
            return target != null && portal.setDestination(target);
        });
    }

    @Override
    public CompletableFuture<Boolean> unlink(UUID portalId) {
        return onPortal(portalId, portal -> {
            portal.unlink();
            return Boolean.TRUE;
        });
    }

    @Override
    public CompletableFuture<Boolean> destroy(UUID portalId) {
        return onPortal(portalId, portal -> {
            portal.destroy();
            return Boolean.TRUE;
        });
    }

    @Override
    public CompletableFuture<Boolean> rename(UUID portalId, String name) {
        return onPortal(portalId, portal -> {
            portal.setName(name);
            portal.save();
            return Boolean.TRUE;
        });
    }

    private static Direction normalOf(Set<Block> cells) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Block cell : cells) {
            minX = Math.min(minX, cell.getX());
            maxX = Math.max(maxX, cell.getX());
            minZ = Math.min(minZ, cell.getZ());
            maxZ = Math.max(maxZ, cell.getZ());
        }
        return maxX - minX >= maxZ - minZ ? Direction.N : Direction.E;
    }

    private static ILocalPortal portal(UUID id) {
        return id == null || Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(id);
    }

    private CompletableFuture<Boolean> onPortal(UUID id, PortalAction action) {
        ILocalPortal portal = portal(id);
        if (portal == null || portal.getCenter() == null) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        return onRegion(portal.getCenter(), () -> action.apply(portal));
    }

    private static <T> CompletableFuture<T> onRegion(Location location, Supplier<T> work) {
        Wormholes plugin = Wormholes.instance;
        CompletableFuture<T> future = new CompletableFuture<>();
        if (plugin == null) {
            future.complete(null);
            return future;
        }
        boolean scheduled = FoliaScheduler.runRegion(plugin, location, () -> {
            try {
                future.complete(work.get());
            } catch (RuntimeException failure) {
                future.completeExceptionally(failure);
            }
        });
        if (!scheduled) {
            future.completeExceptionally(new IllegalStateException("Owning region rejected the mutation"));
        }
        return future;
    }

    private interface PortalAction {
        Boolean apply(ILocalPortal portal);
    }
}
