package art.arcane.wormholes.ops.importers;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.vanilla.PortalFactory;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Builds the portals an importer parsed, on the region thread that owns the site. */
public final class BukkitPortalFactoryBridge implements PortalFactoryBridge {
    private static final long REGION_TIMEOUT_SECONDS = 10L;

    private final UUID owner;
    private final Map<String, UUID> byImportedName = new LinkedHashMap<>();

    public BukkitPortalFactoryBridge(UUID owner) {
        this.owner = owner;
    }

    @Override
    public CreateResult create(ImportedPortal portal) {
        World world = resolveWorld(portal.worldName());
        if (world == null) {
            return CreateResult.refused("world " + portal.worldName() + " is not loaded");
        }
        Wormholes plugin = Wormholes.instance;
        if (plugin == null) {
            return CreateResult.refused("the plugin is not running");
        }
        Location origin = new Location(world, portal.x(), portal.y(), portal.z());
        CompletableFuture<CreateResult> built = new CompletableFuture<>();
        boolean scheduled = FoliaScheduler.runRegion(plugin, origin, () -> {
            try {
                built.complete(build(world, portal));
            } catch (RuntimeException failure) {
                built.completeExceptionally(failure);
            }
        });
        if (!scheduled) {
            return CreateResult.refused("the region owning " + portal.worldName() + " refused the build");
        }
        try {
            CreateResult result = built.get(REGION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result.ok()) {
                byImportedName.put(portal.name(), result.portalId());
            }
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return CreateResult.refused("interrupted while waiting for the region");
        } catch (TimeoutException timedOut) {
            return CreateResult.refused("the region owning " + portal.worldName() + " did not answer in "
                + REGION_TIMEOUT_SECONDS + "s");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            return CreateResult.refused("the build failed: " + cause.getMessage());
        }
    }

    @Override
    public boolean link(UUID source, String destinationName) {
        UUID destinationId = byImportedName.get(destinationName);
        if (destinationId == null || Wormholes.portalManager == null) {
            return false;
        }
        ILocalPortal from = Wormholes.portalManager.getLocalPortal(source);
        ILocalPortal to = Wormholes.portalManager.getLocalPortal(destinationId);
        if (from == null || to == null) {
            return false;
        }
        return PortalFactory.linkOneWay(from, to);
    }

    private CreateResult build(World world, ImportedPortal portal) {
        Set<Block> cells = cells(world, portal);
        if (cells.isEmpty()) {
            return CreateResult.refused("the aperture has no cells");
        }
        ILocalPortal existing = portalCovering(world, cells);
        if (existing != null) {
            return CreateResult.refused("a portal already exists here: " + existing.getName());
        }
        ILocalPortal created = PortalFactory.createFromCells(cells, PortalFrame.canonical(portal.facing()),
            PortalType.PORTAL, portal.name());
        if (created == null) {
            return CreateResult.refused("the portal factory refused the site");
        }
        if (owner != null && created instanceof LocalPortal local) {
            local.setOwner(owner);
            local.save();
        }
        return CreateResult.created(created.getId());
    }

    /**
     * The local portal already occupying any of these cells, so a second import-from run reports the
     * site as taken instead of stacking another portal on the same aperture.
     */
    private static ILocalPortal portalCovering(World world, Set<Block> cells) {
        if (Wormholes.portalManager == null) {
            return null;
        }
        for (ILocalPortal portal : Wormholes.portalManager.getLocalPortals()) {
            PortalStructure structure = portal.getStructure();
            if (structure == null || !world.equals(structure.getWorld())) {
                continue;
            }
            for (Block cell : cells) {
                if (structure.containsBlock(cell.getX(), cell.getY(), cell.getZ())) {
                    return portal;
                }
            }
        }
        return null;
    }

    /** The aperture plane: width along the axis the facing does not use, height upward. */
    private static Set<Block> cells(World world, ImportedPortal portal) {
        Set<Block> cells = new LinkedHashSet<>();
        boolean alongX = portal.facing() == Direction.N || portal.facing() == Direction.S;
        for (int across = 0; across < Math.max(1, portal.width()); across++) {
            for (int up = 0; up < Math.max(1, portal.height()); up++) {
                int x = portal.x() + (alongX ? across : 0);
                int z = portal.z() + (alongX ? 0 : across);
                cells.add(world.getBlockAt(x, portal.y() + up, z));
            }
        }
        return cells;
    }

    private static World resolveWorld(String worldName) {
        World byName = Bukkit.getWorld(worldName);
        return byName != null ? byName : WorldIdentity.resolve(worldName).orElse(null);
    }
}
