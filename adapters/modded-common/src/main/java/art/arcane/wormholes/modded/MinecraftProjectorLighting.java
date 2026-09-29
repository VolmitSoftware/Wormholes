package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.render.ProjectorLighting;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public final class MinecraftProjectorLighting implements ProjectorLighting.Host<ServerPlayer> {
    private final WormholesModRuntime runtime;

    public MinecraftProjectorLighting(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public boolean isOnline(ServerPlayer observer) {
        return !observer.hasDisconnected();
    }

    @Override
    public boolean isChunkSent(ServerPlayer observer, int chunkX, int chunkZ) {
        runtime.requireServerThread();
        return observer.level().getChunkSource().chunkMap.isChunkTracked(observer, chunkX, chunkZ);
    }

    @Override
    public void send(ServerPlayer observer, ProjectorLighting.ChunkLight light) {
        runtime.requireServerThread();
        observer.connection.send(packet(light));
    }

    @Override
    public int sectionBudget() {
        RenderConfig render = runtime.configuration().settings().getRender();
        return ProjectorLighting.lightingSectionBudget(render.adaptiveLighting, Math.clamp(render.lightingMaxSectionsPerPass, 1, 64));
    }

    @Override
    public ProjectionWorldChangeTracker tracker() {
        return runtime.projections().changes();
    }

    static ClientboundLightUpdatePacket packet(ProjectorLighting.ChunkLight light) {
        return new ClientboundLightUpdatePacket(light.chunkX(), light.chunkZ(), new ClientboundLightUpdatePacketData(light.skyMask(),
            light.blockMask(), light.emptySkyMask(), light.emptyBlockMask(), List.of(light.skyArrays()), List.of(light.blockArrays())));
    }
}
