package art.arcane.wormholes.modded;

import art.arcane.wormholes.modded.mixin.ProjectionEntityMapAccess;
import art.arcane.optics.occlusion.LocalOcclusionArbiter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public final class MinecraftEntityVisibility implements LocalOcclusionArbiter.Host<ServerPlayer, Entity> {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final WormholesModRuntime runtime;

    public MinecraftEntityVisibility(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public UUID id(ServerPlayer observer) { return observer.getUUID(); }
    public boolean online(ServerPlayer observer) { return !observer.hasDisconnected(); }
    public boolean valid(Entity entity) { return entity.isAlive() && !entity.isRemoved(); }
    public boolean schedule(ServerPlayer observer, Runnable retry) { return runtime.schedule(retry, 1L); }
    public void failure(IllegalStateException error) { LOGGER.error("Wormholes could not reconcile local entity projection visibility", error); }

    public void hide(ServerPlayer observer, Entity entity) {
        MinecraftEntityTracker tracker = tracker(entity);
        if (tracker != null) {
            tracker.wormholesHide(observer);
        }
    }

    public void show(ServerPlayer observer, Entity entity) {
        MinecraftEntityTracker tracker = tracker(entity);
        if (tracker != null) {
            tracker.wormholesShow(observer);
        }
    }

    private MinecraftEntityTracker tracker(Entity entity) {
        runtime.requireServerThread();
        if (!(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        Object tracker = ((ProjectionEntityMapAccess) level.getChunkSource().chunkMap).wormholesEntityMap().get(entity.getId());
        return tracker instanceof MinecraftEntityTracker projection ? projection : null;
    }
}
