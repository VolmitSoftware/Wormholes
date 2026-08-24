package art.arcane.wormholes.chunk.presend;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class RecordingBukkitChunkPreSend implements AutoCloseable {
    private final AtomicInteger announcements;
    private final AtomicInteger chunks;
    private final Supplier<String> owner;
    private final List<String> announcementOwners;
    private final List<String> chunkOwners;

    private RecordingBukkitChunkPreSend(Supplier<String> owner) {
        announcements = new AtomicInteger();
        chunks = new AtomicInteger();
        this.owner = Objects.requireNonNull(owner, "owner");
        announcementOwners = new ArrayList<String>();
        chunkOwners = new ArrayList<String>();
    }

    public static RecordingBukkitChunkPreSend install() {
        return install(() -> "untracked");
    }

    public static RecordingBukkitChunkPreSend install(Supplier<String> owner) {
        RecordingBukkitChunkPreSend recording = new RecordingBukkitChunkPreSend(owner);
        BukkitChunkPreSendProvider.install(new ChunkPreSendService<World, Player>(
            recording.platform(),
            () -> ChunkPreSendOptions.of(true, 1, 1, 25_000)
        ));
        return recording;
    }

    public int announcements() {
        return announcements.get();
    }

    public int chunks() {
        return chunks.get();
    }

    public List<String> announcementOwners() {
        return List.copyOf(announcementOwners);
    }

    public List<String> chunkOwners() {
        return List.copyOf(chunkOwners);
    }

    @Override
    public void close() {
        BukkitChunkPreSendProvider.shutdown();
    }

    private ChunkPreSendPlatform<World, Player> platform() {
        return new ChunkPreSendPlatform<World, Player>() {
            @Override
            public boolean supported() {
                return true;
            }

            @Override
            public boolean online(Player player) {
                return player.isOnline();
            }

            @Override
            public World world(Player player) {
                return player.getWorld();
            }

            @Override
            public int chunkX(Player player) {
                Location location = player.getLocation();
                return location.getBlockX() >> 4;
            }

            @Override
            public int chunkZ(Player player) {
                Location location = player.getLocation();
                return location.getBlockZ() >> 4;
            }

            @Override
            public int clientViewDistance(Player player) {
                return 0;
            }

            @Override
            public boolean chunkLoaded(World world, int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public boolean regionOwned(
                World world,
                int minChunkX,
                int minChunkZ,
                int maxChunkX,
                int maxChunkZ
            ) {
                return true;
            }

            @Override
            public boolean alreadySent(Player player, int chunkX, int chunkZ) {
                return false;
            }

            @Override
            public boolean announceViewCenter(Player player, int chunkX, int chunkZ) {
                announcements.incrementAndGet();
                announcementOwners.add(owner.get());
                return true;
            }

            @Override
            public boolean sendChunk(Player player, World world, int chunkX, int chunkZ) {
                chunks.incrementAndGet();
                chunkOwners.add(owner.get());
                return true;
            }

            @Override
            public boolean scheduleForRegion(
                World world,
                int chunkX,
                int chunkZ,
                Runnable command,
                long delayTicks
            ) {
                command.run();
                return true;
            }

            @Override
            public long nanoTime() {
                return 0L;
            }
        };
    }
}
