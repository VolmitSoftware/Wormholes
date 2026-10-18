package art.arcane.wormholes.chunk.presend;

import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.chunk.ChunkPacketAccess;
import art.arcane.volmlib.nativelib.chunk.ChunkPosition;
import art.arcane.volmlib.nativelib.chunk.PreparedChunkPacket;
import art.arcane.optics.plate.ChunkLease;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.wormholes.service.WormholesTelemetry;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateViewPosition;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class NativeChunkPreSendDelivery implements ChunkPreSendDelivery {
    private final Plugin plugin;
    private final ChunkPacketAccess packets;
    private final AtomicBoolean viewCenterReported = new AtomicBoolean();
    private final AtomicBoolean chunkReported = new AtomicBoolean();

    public NativeChunkPreSendDelivery(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.packets = NativeAdapters.find(ChunkPacketAccess.class).orElse(null);
        if (!supported()) {
            plugin.getLogger().info("chunk pre-send disabled: native chunk packets are unavailable");
        }
    }

    @Override
    public boolean supported() {
        return packets != null && packets.supported();
    }

    @Override
    public boolean announceViewCenter(Player player, int chunkX, int chunkZ) {
        if (player == null) {
            return false;
        }
        try {
            PacketEvents.getAPI().getPlayerManager()
                .sendPacket(player, new WrapperPlayServerUpdateViewPosition(chunkX, chunkZ));
            WormholesTelemetry.countPacket();
            return true;
        } catch (Throwable failure) {
            report(viewCenterReported, "PRESEND_VIEW_CENTER_DELIVERY_FAILED",
                "chunk pre-send view centre delivery failed", failure);
            return false;
        }
    }

    @Override
    public boolean sendChunk(Player player, World world, int chunkX, int chunkZ) {
        if (!supported()) {
            return false;
        }
        try {
            if (!packets.sendChunk(player, world, chunkX, chunkZ)) {
                return false;
            }
            WormholesTelemetry.countPacket();
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            report(chunkReported, "PRESEND_CHUNK_DELIVERY_FAILED",
                "chunk pre-send chunk delivery failed", failure);
            return false;
        }
    }

    @Override
    public ChunkPreparedPacket<Player> prepareChunk(World world, int chunkX, int chunkZ) {
        if (!supported()) {
            return null;
        }
        ChunkLease lease = BukkitChunkLeaseProvider.registry().retain(world, world.getUID(), chunkX, chunkZ);
        try {
            WorldChangeTracker tracker = Wormholes.projectionChangeTracker;
            long version = tracker == null ? 0L : tracker.currentVersion();
            PreparedChunkPacket packet = packets.prepare(world, new ChunkPosition(chunkX, chunkZ)).orElse(null);
            if (packet == null) {
                lease.close();
                return null;
            }
            Capture capture = new Capture(packet, lease, tracker, world.getUID(), chunkX, chunkZ, version,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250));
            return new Captured(capture);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            lease.close();
            report(chunkReported, "ARRIVAL_CHUNK_CAPTURE_FAILED", "Arrival chunk preparation failed", failure);
            return null;
        }
    }

    private void report(AtomicBoolean latch, String reason, String message, Throwable failure) {
        WormholesTelemetry.countFailure(reason);
        if (latch.compareAndSet(false, true)) {
            plugin.getLogger().log(Level.WARNING, message, failure);
        }
    }

    private record Capture(PreparedChunkPacket packet, ChunkLease lease, WorldChangeTracker tracker,
                           UUID world, int x, int z, long version, long expiry) {
    }

    private final class Captured implements ChunkPreparedPacket<Player> {
        private final Capture capture;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Captured(Capture capture) {
            this.capture = capture;
        }

        @Override
        public boolean ready() {
            return capture.packet().ready();
        }

        @Override
        public boolean valid() {
            return System.nanoTime() <= capture.expiry()
                && capture.tracker() == Wormholes.projectionChangeTracker
                && (capture.tracker() == null || !capture.tracker().dirtySince(capture.world(),
                    capture.x() - 1, capture.z() - 1, capture.x() + 1, capture.z() + 1, capture.version()));
        }

        @Override
        public CompletionStage<Boolean> send(Player player) {
            return capture.packet().send(player).whenComplete((written, failure) -> {
                if (failure != null) {
                    report(chunkReported, "ARRIVAL_CHUNK_WRITE_FAILED", "Arrival chunk write failed", failure);
                } else if (Boolean.TRUE.equals(written)) {
                    WormholesTelemetry.countPacket();
                }
            });
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                capture.lease().close();
            }
        }
    }
}
