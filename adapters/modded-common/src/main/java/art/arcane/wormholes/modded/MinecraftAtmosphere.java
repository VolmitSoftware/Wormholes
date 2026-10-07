package art.arcane.wormholes.modded;

import art.arcane.optics.claim.WorldOutput;
import art.arcane.optics.math.CellKeys;

import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.optics.fidelity.AtmosphereChannel;
import art.arcane.optics.fidelity.BiomeClaimSet;
import art.arcane.optics.view.ContentView;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MinecraftAtmosphere implements AutoCloseable {
    private final WormholesModRuntime runtime;
    private final ServerPlayer observer;
    private final ServerLevel world;
    private final WorldOutput<ServerPlayer> output;
    private final BiomeClaimSet claims;
    private final Map<UUID, AtmosphereChannel<BlockState, ContentView<BlockState, BlockState>>> channels = new HashMap<>();
    private final Long2ObjectMap<BiomeClaimSet.ChunkBiomes> pending = new Long2ObjectOpenHashMap<>();

    public MinecraftAtmosphere(WormholesModRuntime runtime, Context context) {
        this.runtime = runtime;
        this.observer = context.observer();
        this.world = observer.level();
        this.output = context.output();
        this.claims = new BiomeClaimSet(context.local().getMinHeight(), context.local().getMaxHeight(), context.local());
    }

    public void update(MinecraftPortalProjector projector, boolean changed) {
        runtime.requireServerThread();
        UUID id = projector.portalId();
        AtmosphereChannel<BlockState, ContentView<BlockState, BlockState>> channel = channels.computeIfAbsent(id,
            ignored -> new AtmosphereChannel<>());
        if (!FidelitySettings.biomeTint || !projector.atmosphereMode().tintsBiomes()) {
            if (channel.disable()) {
                enqueue(claims.release(id));
            }
            return;
        }
        Long2IntOpenHashMap overrides = channel.update(new AtmosphereChannel.Scan<>(projector.scan().claims(),
            projector.destinationView(), changed), FidelitySettings.snapshot());
        if (overrides != null) {
            enqueue(claims.apply(id, overrides));
        }
    }

    public void remove(UUID portalId) {
        channels.remove(portalId);
        enqueue(claims.release(portalId));
    }

    public void resend(int chunkX, int chunkZ) {
        if (!claims.isEmpty()) {
            enqueue(List.of(claims.current(chunkX, chunkZ)));
        }
    }

    public void flush() {
        runtime.requireServerThread();
        if (observer.hasDisconnected() || observer.level() != world || pending.isEmpty()) {
            return;
        }
        ArrayList<BiomeClaimSet.ChunkBiomes> ready = new ArrayList<>(pending.size());
        Iterator<Long2ObjectMap.Entry<BiomeClaimSet.ChunkBiomes>> iterator = pending.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            BiomeClaimSet.ChunkBiomes chunk = iterator.next().getValue();
            if (world.getChunkSource().chunkMap.isChunkTracked(observer, chunk.chunkX(), chunk.chunkZ())) {
                ready.add(chunk);
                iterator.remove();
            }
        }
        if (!ready.isEmpty()) {
            output.biomes(observer, ready);
        }
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        for (UUID portalId : channels.keySet()) {
            enqueue(claims.release(portalId));
        }
        flush();
        channels.clear();
        claims.clear();
        pending.clear();
    }

    private void enqueue(List<BiomeClaimSet.ChunkBiomes> chunks) {
        for (BiomeClaimSet.ChunkBiomes chunk : chunks) {
            pending.put(CellKeys.chunkKey(chunk.chunkX(), chunk.chunkZ()), chunk);
        }
    }

    public record Context(ServerPlayer observer, MinecraftProjectionWorldView local, WorldOutput<ServerPlayer> output) {
    }
}
