package art.arcane.wormholes.chunk.presend;

import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.chunk.ChunkPacketAccess;
import art.arcane.wormholes.service.WormholesTelemetry;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateViewPosition;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
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

    private void report(AtomicBoolean latch, String reason, String message, Throwable failure) {
        WormholesTelemetry.countFailure(reason);
        if (latch.compareAndSet(false, true)) {
            plugin.getLogger().log(Level.WARNING, message, failure);
        }
    }
}
