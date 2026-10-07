package art.arcane.wormholes.render;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import org.bukkit.Particle;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateLight;

import art.arcane.optics.claim.ProjectionOutput;
import art.arcane.optics.fidelity.AcousticsBridge;
import art.arcane.optics.fidelity.BiomeClaimSet;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.fidelity.WeatherRelay;
import art.arcane.optics.light.ProjectorLighting;
import art.arcane.optics.math.CellKeys;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.render.acoustics.SoundPacketSink;
import art.arcane.wormholes.render.atmosphere.ChunkBiomesPacketSink;
import art.arcane.wormholes.render.blockentity.BlockEntityPacketSink;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.service.WormholesTelemetry;

public final class BukkitProjectionOutput implements ProjectionOutput<Player> {
    private final ProjectionChunkVisibility visibility;
    private final Function<UUID, List<Player>> observers;
    private final LightPacketSender lightSender;
    private final BlockEntityPacketSink blockEntities;
    private final SoundPacketSink sounds;
    private final ChunkBiomesPacketSink biomes;

    public BukkitProjectionOutput(ProjectionChunkVisibility visibility, Function<UUID, List<Player>> observers) {
        this(visibility, observers, BukkitProjectionOutput::sendLight);
    }

    public BukkitProjectionOutput(ProjectionChunkVisibility visibility, Function<UUID, List<Player>> observers,
                                  LightPacketSender lightSender) {
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        this.observers = Objects.requireNonNull(observers, "observers");
        this.lightSender = Objects.requireNonNull(lightSender, "lightSender");
        this.blockEntities = new BlockEntityPacketSink();
        this.sounds = new SoundPacketSink();
        this.biomes = new ChunkBiomesPacketSink();
    }

    public ProjectionChunkVisibility visibility() {
        return visibility;
    }

    public ProjectorLighting<Player, BlockData, ProjectionWorldView> lighting() {
        return new ProjectorLighting<Player, BlockData, ProjectionWorldView>(this, () -> Wormholes.projectionChangeTracker,
            WormholesTelemetry.metrics());
    }

    @Override
    public boolean online(Player observer) {
        return observer.isOnline();
    }

    @Override
    public boolean chunkSent(Player observer, int chunkX, int chunkZ) {
        return visibility.isChunkSent(observer, chunkX, chunkZ);
    }

    @Override
    public void light(Player observer, ProjectorLighting.ChunkLight light) {
        lightSender.send(observer, new LightPacket(light.chunkX(), light.chunkZ(), new LightData(true, light.blockMask(), light.skyMask(),
            light.emptyBlockMask(), light.emptySkyMask(), light.skyArrays().length, light.blockArrays().length,
            light.skyArrays(), light.blockArrays())));
    }

    @Override
    public int lightSectionBudget() {
        return ProjectorLighting.lightingSectionBudget(Settings.ADAPTIVE_LIGHTING, Settings.LIGHTING_MAX_SECTIONS_PER_PASS);
    }

    @Override
    public void blockEntity(Player observer, long cellKey, BlockEntitySample sample) {
        blockEntities.send(observer, cellKey, sample);
    }

    @Override
    public void biomes(Player observer, List<BiomeClaimSet.ChunkBiomes> chunks) {
        biomes.send(observer, chunks);
    }

    @Override
    public void weather(Player observer, WeatherRelay.Precipitation particle, long cellKey) {
        observer.spawnParticle(particle == WeatherRelay.Precipitation.SNOWFLAKE ? Particle.SNOWFLAKE : Particle.RAIN,
            CellKeys.unpackX(cellKey) + 0.5D, CellKeys.unpackY(cellKey) + 0.5D, CellKeys.unpackZ(cellKey) + 0.5D,
            1, 0.4D, 0.5D, 0.4D, 0.0D);
    }

    @Override
    public void sound(Player observer, AcousticsBridge.Playback playback) {
        sounds.play(observer, playback);
    }

    @Override
    public boolean clientAmbient(Player observer) {
        return sounds.clientAmbient(observer);
    }

    @Override
    public List<Player> observersOf(UUID endpointId) {
        return observers.apply(endpointId);
    }

    private static void sendLight(Player observer, LightPacket packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, new WrapperPlayServerUpdateLight(packet.chunkX(), packet.chunkZ(), packet.data()));
    }

    @FunctionalInterface
    public interface LightPacketSender {
        void send(Player observer, LightPacket packet);
    }

    public record LightPacket(int chunkX, int chunkZ, LightData data) {
    }
}
