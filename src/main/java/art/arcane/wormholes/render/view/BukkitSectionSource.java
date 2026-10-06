package art.arcane.wormholes.render.view;

import art.arcane.optics.math.CellKeys;

import art.arcane.wormholes.platform.WormholesPlatform;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import art.arcane.optics.view.CachedSection;
import art.arcane.optics.view.SectionSource;

final class BukkitSectionSource implements SectionSource<BlockData, Material> {
    private final World world;
    private final BlockData air;
    private final Long2ObjectOpenHashMap<ChunkSnapshot> snapshots;
    private final BlockData[] singleStates;
    private final boolean[] singleStateKnown;

    BukkitSectionSource(World world, BlockData air) {
        this.world = world;
        this.air = air;
        this.snapshots = new Long2ObjectOpenHashMap<ChunkSnapshot>(16);
        int materials = Material.values().length;
        this.singleStates = new BlockData[materials];
        this.singleStateKnown = new boolean[materials];
    }

    @Override
    public boolean columnAvailable(int chunkX, int chunkZ) {
        return snapshots.containsKey(CellKeys.chunkKey(chunkX, chunkZ)) || world.isChunkLoaded(chunkX, chunkZ);
    }

    @Override
    public boolean capture(int sectionX, int sectionY, int sectionZ, CachedSection.Builder<BlockData, Material> builder) {
        ChunkSnapshot snapshot = snapshot(sectionX, sectionZ);
        if (snapshot == null) {
            return false;
        }
        int baseY = sectionY << 4;
        for (int localY = 0; localY < 16; localY++) {
            int y = baseY + localY;
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    Material material = snapshot.getBlockType(localX, y, localZ);
                    BlockData data = ProjectionWorldView.isAir(material) ? air : blockData(snapshot, localX, y, localZ, material);
                    builder.set(CachedSection.index(localX, localY, localZ), data, material);
                }
            }
        }
        return true;
    }

    @Override
    public void discardColumn(int chunkX, int chunkZ) {
        snapshots.remove(CellKeys.chunkKey(chunkX, chunkZ));
    }

    @Override
    public void endTick() {
        snapshots.clear();
    }

    private ChunkSnapshot snapshot(int chunkX, int chunkZ) {
        long key = CellKeys.chunkKey(chunkX, chunkZ);
        ChunkSnapshot snapshot = snapshots.get(key);
        if (snapshot != null) {
            return snapshot;
        }
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return null;
        }
        snapshot = WormholesPlatform.chunkSnapshot(world.getChunkAt(chunkX, chunkZ), false, false, false, false);
        snapshots.put(key, snapshot);
        return snapshot;
    }

    private BlockData blockData(ChunkSnapshot snapshot, int localX, int y, int localZ, Material material) {
        int ordinal = material.ordinal();
        if (!singleStateKnown[ordinal]) {
            singleStates[ordinal] = singleState(material);
            singleStateKnown[ordinal] = true;
        }
        BlockData shared = singleStates[ordinal];
        return shared != null ? shared : snapshot.getBlockData(localX, y, localZ);
    }

    private static BlockData singleState(Material material) {
        try {
            BlockData defaults = material.createBlockData();
            return defaults.getAsString().indexOf('[') < 0 ? defaults : null;
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }
}
