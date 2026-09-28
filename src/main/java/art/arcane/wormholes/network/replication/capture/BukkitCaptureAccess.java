package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.render.view.OccludedMarker;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.UUID;

public enum BukkitCaptureAccess implements CaptureAccess<World, BlockData> {
    INSTANCE;

    @Override
    public UUID worldId(World world) {
        return world.getUID();
    }

    @Override
    public World world(UUID id) {
        return Bukkit.getWorld(id);
    }

    @Override
    public int minHeight(World world) {
        return world.getMinHeight();
    }

    @Override
    public int maxHeight(World world) {
        return world.getMaxHeight();
    }

    @Override
    public BlockData block(World world, int x, int y, int z) {
        return world.getBlockAt(x, y, z).getBlockData();
    }

    @Override
    public boolean occluding(BlockData block) {
        return OccludedMarker.isOccluding(block);
    }

    @Override
    public String blockKey(BlockData block) {
        return block.getAsString();
    }

    @Override
    public BlockData copy(BlockData block) {
        return block.clone();
    }

    @Override
    public boolean debugEnabled() {
        return Settings.DEBUG;
    }

    @Override
    public void debug(String message) {
        Wormholes.v(message);
    }
}
