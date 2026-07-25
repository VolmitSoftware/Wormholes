package art.arcane.wormholes.chunk.presend;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.platform.WormholesPlatform;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

public final class BukkitChunkPreSendPlatform implements ChunkPreSendPlatform<World, Player> {
    private final Plugin plugin;
    private final ChunkPreSendDelivery delivery;
    private final Operations operations;
    private final ThreadLocal<double[]> position;

    public BukkitChunkPreSendPlatform(Plugin plugin) {
        this(plugin, new NmsChunkPreSendDelivery(plugin), new BukkitOperations());
    }

    public BukkitChunkPreSendPlatform(Plugin plugin, ChunkPreSendDelivery delivery, Operations operations) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.position = ThreadLocal.withInitial(() -> new double[5]);
    }

    @Override
    public boolean supported() {
        return delivery.supported();
    }

    @Override
    public boolean online(Player player) {
        return player != null && player.isOnline();
    }

    @Override
    public World world(Player player) {
        return player == null ? null : player.getWorld();
    }

    @Override
    public int chunkX(Player player) {
        return blockCoordinate(player, 0) >> 4;
    }

    @Override
    public int chunkZ(Player player) {
        return blockCoordinate(player, 2) >> 4;
    }

    @Override
    public int clientViewDistance(Player player) {
        int distance = operations.sendViewDistance(player);
        return distance > 0 ? distance : operations.serverViewDistance();
    }

    @Override
    public boolean chunkLoaded(World world, int chunkX, int chunkZ) {
        return world != null && operations.chunkLoaded(world, chunkX, chunkZ);
    }

    @Override
    public boolean regionOwned(World world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        return world != null && operations.regionOwned(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
    }

    @Override
    public boolean alreadySent(Player player, int chunkX, int chunkZ) {
        return operations.sentChunkQuerySupported() && operations.chunkSent(player, chunkX, chunkZ);
    }

    @Override
    public boolean announceViewCenter(Player player, int chunkX, int chunkZ) {
        return delivery.announceViewCenter(player, chunkX, chunkZ);
    }

    @Override
    public boolean sendChunk(Player player, World world, int chunkX, int chunkZ) {
        return delivery.sendChunk(player, world, chunkX, chunkZ);
    }

    @Override
    public boolean scheduleForRegion(World world, int chunkX, int chunkZ, Runnable command, long delayTicks) {
        if (world == null || command == null) {
            return false;
        }
        return operations.runRegion(plugin, world, chunkX, chunkZ, command, Math.max(1L, delayTicks));
    }

    @Override
    public long nanoTime() {
        return operations.nanoTime();
    }

    private int blockCoordinate(Player player, int index) {
        if (player == null) {
            return 0;
        }
        double[] scratch = position.get();
        WormholesPlatform.entityPosition(player, scratch);
        return (int) Math.floor(scratch[index]);
    }

    public interface Operations {
        int sendViewDistance(Player player);

        int serverViewDistance();

        boolean chunkLoaded(World world, int chunkX, int chunkZ);

        boolean regionOwned(World world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ);

        boolean sentChunkQuerySupported();

        boolean chunkSent(Player player, int chunkX, int chunkZ);

        boolean runRegion(Plugin plugin, World world, int chunkX, int chunkZ, Runnable command, long delayTicks);

        long nanoTime();
    }

    private static final class BukkitOperations implements Operations {
        @Override
        public int sendViewDistance(Player player) {
            return WormholesPlatform.sendViewDistance(player);
        }

        @Override
        public int serverViewDistance() {
            return Bukkit.getViewDistance();
        }

        @Override
        public boolean chunkLoaded(World world, int chunkX, int chunkZ) {
            return world.isChunkLoaded(chunkX, chunkZ);
        }

        @Override
        public boolean regionOwned(World world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
            return WormholesPlatform.isOwnedByCurrentRegion(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
        }

        @Override
        public boolean sentChunkQuerySupported() {
            return WormholesPlatform.supportsSentChunkQuery();
        }

        @Override
        public boolean chunkSent(Player player, int chunkX, int chunkZ) {
            return WormholesPlatform.isChunkSent(player, chunkX, chunkZ);
        }

        @Override
        public boolean runRegion(
            Plugin plugin,
            World world,
            int chunkX,
            int chunkZ,
            Runnable command,
            long delayTicks
        ) {
            return FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ, command, delayTicks);
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    }
}
