package art.arcane.wormholes.render;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.service.WormholesTelemetry;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateLight;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

public final class BukkitProjectorLighting implements ProjectorLighting.Host<Player> {
    private final ProjectionChunkVisibility visibility;
    private final LightPacketSender sender;

    private BukkitProjectorLighting(ProjectionChunkVisibility visibility, LightPacketSender sender) {
        this.visibility = visibility;
        this.sender = sender;
    }

    public static ProjectorLighting<Player, BlockData, ProjectionWorldView> create() {
        return create(WormholesPlatform::isChunkSent);
    }

    public static ProjectorLighting<Player, BlockData, ProjectionWorldView> create(ProjectionChunkVisibility visibility) {
        return create(visibility, BukkitProjectorLighting::sendPacket);
    }

    public static ProjectorLighting<Player, BlockData, ProjectionWorldView> create(ProjectionChunkVisibility visibility, LightPacketSender sender) {
        return new ProjectorLighting<>(new BukkitProjectorLighting(visibility, sender), WormholesTelemetry.metrics());
    }

    @Override
    public boolean isOnline(Player observer) {
        return observer.isOnline();
    }

    @Override
    public boolean isChunkSent(Player observer, int chunkX, int chunkZ) {
        return visibility.isChunkSent(observer, chunkX, chunkZ);
    }

    @Override
    public void send(Player observer, ProjectorLighting.ChunkLight light) {
        sender.send(observer, light.chunkX(), light.chunkZ(), new LightData(true, light.blockMask(), light.skyMask(),
            light.emptyBlockMask(), light.emptySkyMask(), light.skyArrays().length, light.blockArrays().length,
            light.skyArrays(), light.blockArrays()));
    }

    @Override
    public int sectionBudget() {
        return ProjectorLighting.lightingSectionBudget(Settings.ADAPTIVE_LIGHTING, Settings.LIGHTING_MAX_SECTIONS_PER_PASS);
    }

    @Override
    public ProjectionWorldChangeTracker tracker() {
        return Wormholes.projectionChangeTracker;
    }

    private static void sendPacket(Player observer, int chunkX, int chunkZ, LightData data) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerUpdateLight(chunkX, chunkZ, data));
    }

    @FunctionalInterface
    public interface LightPacketSender {
        void send(Player observer, int chunkX, int chunkZ, LightData data);
    }
}
