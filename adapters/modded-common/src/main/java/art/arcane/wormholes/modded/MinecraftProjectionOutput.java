package art.arcane.wormholes.modded;

import java.util.List;
import java.util.UUID;

import art.arcane.optics.claim.WorldOutput;
import art.arcane.optics.fidelity.AcousticsBridge;
import art.arcane.optics.fidelity.BiomeClaimSet;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.fidelity.WeatherRelay;
import art.arcane.optics.light.LightOverlay;
import art.arcane.optics.math.CellKeys;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;

public final class MinecraftProjectionOutput implements WorldOutput<ServerPlayer> {
    private final WormholesModRuntime runtime;
    private final ServerPlayer viewer;
    private final MinecraftBlockEntityPackets blockEntities;
    private Registry<Biome> biomeRegistry;

    public MinecraftProjectionOutput(WormholesModRuntime runtime, ServerPlayer viewer) {
        this.runtime = runtime;
        this.viewer = viewer;
        this.blockEntities = new MinecraftBlockEntityPackets(runtime);
    }

    @Override
    public boolean online(ServerPlayer observer) {
        return !observer.hasDisconnected();
    }

    @Override
    public boolean chunkSent(ServerPlayer observer, int chunkX, int chunkZ) {
        runtime.requireServerThread();
        return observer.level().getChunkSource().chunkMap.isChunkTracked(observer, chunkX, chunkZ);
    }

    @Override
    public void light(ServerPlayer observer, LightOverlay.ChunkLight light) {
        runtime.requireServerThread();
        observer.connection.send(lightPacket(light));
    }

    @Override
    public int lightSectionBudget() {
        RenderConfig render = runtime.configuration().settings().getRender();
        return LightOverlay.lightingSectionBudget(render.adaptiveLighting, Math.clamp(render.lightingMaxSectionsPerPass, 1, 64));
    }

    @Override
    public void blockEntity(ServerPlayer observer, long cellKey, BlockEntitySample sample) {
        blockEntities.send(observer, cellKey, sample);
    }

    @Override
    public void biomes(ServerPlayer observer, List<BiomeClaimSet.ChunkBiomes> chunks) {
        observer.connection.send(MinecraftBiomePackets.packet(biomeRegistry(), chunks));
    }

    @Override
    public void weather(ServerPlayer observer, WeatherRelay.Precipitation particle, long cellKey) {
        observer.level().sendParticles(observer, particle == WeatherRelay.Precipitation.SNOWFLAKE ? ParticleTypes.SNOWFLAKE : ParticleTypes.RAIN,
            false, false, CellKeys.unpackX(cellKey) + 0.5D, CellKeys.unpackY(cellKey) + 0.5D, CellKeys.unpackZ(cellKey) + 0.5D,
            1, 0.4D, 0.5D, 0.4D, 0.0D);
    }

    @Override
    public void sound(ServerPlayer observer, AcousticsBridge.Playback playback) {
        if (runtime.clientViews().receiver(observer)) {
            runtime.clientViews().oneShot(observer, ClientViewEmitters.sound(playback, 0));
            return;
        }
        MinecraftAcoustics.play(observer, playback);
    }

    @Override
    public boolean clientAmbient(ServerPlayer observer) {
        return runtime.clientViews().receiver(observer);
    }

    @Override
    public List<ServerPlayer> observersOf(UUID endpointId) {
        return List.of(viewer);
    }

    static ClientboundLightUpdatePacket lightPacket(LightOverlay.ChunkLight light) {
        return new ClientboundLightUpdatePacket(light.chunkX(), light.chunkZ(), new ClientboundLightUpdatePacketData(light.skyMask(),
            light.blockMask(), light.emptySkyMask(), light.emptyBlockMask(), List.of(light.skyArrays()), List.of(light.blockArrays())));
    }

    private Registry<Biome> biomeRegistry() {
        Registry<Biome> registry = biomeRegistry;
        if (registry == null) {
            registry = viewer.level().registryAccess().lookupOrThrow(Registries.BIOME);
            biomeRegistry = registry;
        }
        return registry;
    }
}
