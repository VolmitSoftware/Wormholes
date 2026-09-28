package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.render.ProjectorLighting;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerPlayer;

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
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(light.chunkX());
            buffer.writeVarInt(light.chunkZ());
            buffer.writeBitSet(light.skyMask());
            buffer.writeBitSet(light.blockMask());
            buffer.writeBitSet(light.emptySkyMask());
            buffer.writeBitSet(light.emptyBlockMask());
            buffer.writeVarInt(light.skyArrays().length);
            for (byte[] sky : light.skyArrays()) {
                buffer.writeByteArray(sky);
            }
            buffer.writeVarInt(light.blockArrays().length);
            for (byte[] block : light.blockArrays()) {
                buffer.writeByteArray(block);
            }
            return ClientboundLightUpdatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }
}
