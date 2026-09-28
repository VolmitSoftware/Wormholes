package art.arcane.wormholes.modded;

import art.arcane.wormholes.chunk.presend.ChunkPreSendPlatform;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

public final class MinecraftChunkPreSendPlatform implements ChunkPreSendPlatform<ServerLevel, ServerPlayer> {
    private final WormholesModRuntime runtime;

    public MinecraftChunkPreSendPlatform(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public boolean supported() {
        return runtime.running();
    }

    @Override
    public boolean online(ServerPlayer player) {
        runtime.requireServerThread();
        return !player.hasDisconnected() && !player.isRemoved();
    }

    @Override
    public ServerLevel world(ServerPlayer player) {
        runtime.requireServerThread();
        return player.level();
    }

    @Override
    public int chunkX(ServerPlayer player) {
        runtime.requireServerThread();
        return player.chunkPosition().x();
    }

    @Override
    public int chunkZ(ServerPlayer player) {
        runtime.requireServerThread();
        return player.chunkPosition().z();
    }

    @Override
    public int clientViewDistance(ServerPlayer player) {
        runtime.requireServerThread();
        return Math.min(player.requestedViewDistance(), runtime.server().getPlayerList().getViewDistance());
    }

    @Override
    public int sectionCount(ServerLevel world) {
        runtime.requireServerThread();
        return world.getSectionsCount();
    }

    @Override
    public boolean chunkLoaded(ServerLevel world, int chunkX, int chunkZ) {
        runtime.requireServerThread();
        return world.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
    }

    @Override
    public boolean regionOwned(ServerLevel world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        return runtime.server().isSameThread() && world.getServer() == runtime.server();
    }

    @Override
    public boolean alreadySent(ServerPlayer player, int chunkX, int chunkZ) {
        runtime.requireServerThread();
        return player.level().getChunkSource().chunkMap.isChunkTracked(player, chunkX, chunkZ);
    }

    @Override
    public boolean announceViewCenter(ServerPlayer player, int chunkX, int chunkZ) {
        if (!online(player)) {
            return false;
        }
        player.connection.send(new ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ));
        return true;
    }

    @Override
    public boolean sendChunk(ServerPlayer player, ServerLevel world, int chunkX, int chunkZ) {
        if (!online(player)) {
            return false;
        }
        LevelChunk chunk = world.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return false;
        }
        player.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, world.getLightEngine(), null, null));
        return true;
    }

    @Override
    public boolean scheduleForRegion(ServerLevel world, int chunkX, int chunkZ, Runnable command, long delayTicks) {
        return world.getServer() == runtime.server() && runtime.schedule(command, delayTicks);
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}
