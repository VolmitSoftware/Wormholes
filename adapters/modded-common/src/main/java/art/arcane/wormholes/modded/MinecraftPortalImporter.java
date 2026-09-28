package art.arcane.wormholes.modded;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.ops.importers.ImportedPortal;
import art.arcane.wormholes.ops.importers.PortalFactoryBridge;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class MinecraftPortalImporter implements PortalFactoryBridge {
    private final Options options;
    private final MinecraftServer server;
    private final Map<String, UUID> imported = new LinkedHashMap<>();

    MinecraftPortalImporter(Options options) {
        this.options = options;
        server = options.runtime().server();
    }

    @Override
    public CreateResult create(ImportedPortal portal) {
        return onServer(() -> build(portal));
    }

    @Override
    public boolean link(UUID source, String destinationName) {
        return onServer(() -> {
            if (!options.active().getAsBoolean()) {
                return false;
            }
            MinecraftPortalRegistry registry = options.runtime().portals();
            MinecraftPortal from = registry.get(source);
            UUID destination = imported.get(destinationName);
            MinecraftPortal to = destination == null ? null : registry.get(destination);
            if (from == null || to == null) {
                return false;
            }
            from.link(to);
            registry.save(from);
            return true;
        });
    }

    private CreateResult build(ImportedPortal portal) {
        if (!options.active().getAsBoolean()) {
            return CreateResult.refused("The runtime stopped during the import");
        }
        ServerLevel level = resolveWorld(portal.worldName());
        if (level == null) {
            return CreateResult.refused("World " + portal.worldName() + " is not loaded");
        }
        if (portal.width() < 1 || portal.height() < 1 || (long) portal.width() * portal.height() > Integer.MAX_VALUE
            || portal.y() < level.getMinY() || (long) portal.y() + portal.height() > level.getMaxY()) {
            return CreateResult.refused("Aperture has invalid dimensions or exceeds world height");
        }
        List<BlockPos> cells = new ArrayList<>(portal.width() * portal.height());
        boolean alongX = portal.facing() == Direction.N || portal.facing() == Direction.S;
        for (int across = 0; across < portal.width(); across++) {
            for (int up = 0; up < portal.height(); up++) {
                BlockPos cell = new BlockPos(portal.x() + (alongX ? across : 0), portal.y() + up,
                    portal.z() + (alongX ? 0 : across));
                if (!level.getWorldBorder().isWithinBounds(cell)) {
                    return CreateResult.refused("Aperture exceeds the world border");
                }
                cells.add(cell);
            }
        }
        GeometryVector normal = portal.facing().toVector();
        try {
            MinecraftPortal created = options.runtime().portals().create(options.owner(), level, cells,
                PortalType.PORTAL, new Vec3(normal.x(), normal.y(), normal.z()));
            created.setName(portal.name());
            options.runtime().portals().save(created);
            imported.put(portal.name(), created.getId());
            return CreateResult.created(created.getId());
        } catch (IllegalArgumentException refused) {
            return CreateResult.refused(refused.getMessage());
        }
    }

    private ServerLevel resolveWorld(String worldName) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(worldName)
                || level.dimension().identifier().getPath().equals(worldName)) {
                return level;
            }
        }
        String name = server.getWorldData().getLevelName();
        if (name.equals(worldName)) {
            return server.getLevel(Level.OVERWORLD);
        }
        if ((name + "_nether").equals(worldName)) {
            return server.getLevel(Level.NETHER);
        }
        return (name + "_the_end").equals(worldName) ? server.getLevel(Level.END) : null;
    }

    private <T> T onServer(Supplier<T> action) {
        return server.isSameThread() ? action.get() : CompletableFuture.supplyAsync(action, server).join();
    }

    record Options(WormholesModRuntime runtime, UUID owner, BooleanSupplier active) {
    }
}
