package art.arcane.wormholes.modded;

import art.arcane.optics.math.Box;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class MinecraftPortalCandidates {
    private static final double CELL_SIZE = 128.0D;
    static final MinecraftPortalCandidates EMPTY = new MinecraftPortalCandidates(Map.of());

    private final Map<ResourceKey<Level>, Long2ObjectMap<List<MinecraftPortal>>> worlds;

    private MinecraftPortalCandidates(Map<ResourceKey<Level>, Long2ObjectMap<List<MinecraftPortal>>> worlds) {
        this.worlds = worlds;
    }

    static MinecraftPortalCandidates capture(Collection<MinecraftPortal> portals, double padding) {
        Map<ResourceKey<Level>, Long2ObjectOpenHashMap<List<MinecraftPortal>>> indexed = new HashMap<>();
        for (MinecraftPortal portal : portals) {
            Box area = portal.getGeometry().getArea();
            Identifier dimension = Identifier.tryParse(portal.getWorldKey());
            if (area == null || dimension == null) {
                continue;
            }
            ResourceKey<Level> world = ResourceKey.create(Registries.DIMENSION, dimension);
            Long2ObjectOpenHashMap<List<MinecraftPortal>> cells = indexed.computeIfAbsent(world, ignored -> new Long2ObjectOpenHashMap<>());
            int maxX = cell(area.getXb() + padding);
            int maxZ = cell(area.getZb() + padding);
            for (int x = cell(area.getXa() - padding); x <= maxX; x++) {
                for (int z = cell(area.getZa() - padding); z <= maxZ; z++) {
                    cells.computeIfAbsent(key(x, z), ignored -> new ArrayList<>()).add(portal);
                }
            }
        }
        if (indexed.isEmpty()) {
            return EMPTY;
        }
        Map<ResourceKey<Level>, Long2ObjectMap<List<MinecraftPortal>>> frozen = new HashMap<>(indexed.size() * 2);
        for (Map.Entry<ResourceKey<Level>, Long2ObjectOpenHashMap<List<MinecraftPortal>>> world : indexed.entrySet()) {
            Long2ObjectOpenHashMap<List<MinecraftPortal>> cells = world.getValue();
            for (Long2ObjectMap.Entry<List<MinecraftPortal>> cell : cells.long2ObjectEntrySet()) {
                cell.setValue(List.copyOf(cell.getValue()));
            }
            cells.trim();
            frozen.put(world.getKey(), cells);
        }
        return new MinecraftPortalCandidates(frozen);
    }

    List<MinecraftPortal> near(ResourceKey<Level> world, double x, double z) {
        Long2ObjectMap<List<MinecraftPortal>> cells = worlds.get(world);
        if (cells == null) {
            return List.of();
        }
        List<MinecraftPortal> portals = cells.get(key(cell(x), cell(z)));
        return portals == null ? List.of() : portals;
    }

    private static int cell(double coordinate) {
        return (int) Math.floor(coordinate / CELL_SIZE);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
