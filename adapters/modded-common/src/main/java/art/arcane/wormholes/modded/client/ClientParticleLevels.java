package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.mixin.client.ParticleEngineAccess;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TrackingEmitter;
import net.minecraft.core.particles.ParticleLimit;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class ClientParticleLevels {
    private final Map<ClientLevel, Stash> stashes = new IdentityHashMap<>();

    void swap(ParticleEngineAccess engine, ClientLevel destination) {
        ClientLevel source = engine.wormholes$level();
        if (source == destination) {
            return;
        }
        Stash taken = Stash.take(engine);
        if (source != null && !taken.empty()) {
            stashes.put(source, taken);
        }
        Stash restored = stashes.remove(destination);
        if (restored != null) {
            restored.restore(engine);
        }
        engine.wormholes$level(destination);
    }

    void forget(ClientLevel level) {
        stashes.remove(level);
    }

    void clear() {
        stashes.clear();
    }

    int stashed() {
        return stashes.size();
    }

    private record Stash(Map<ParticleRenderType, ParticleGroup<?>> groups, List<TrackingEmitter> emitters, List<Particle> pending,
                         Object2IntOpenHashMap<ParticleLimit> counts) {
        private static Stash take(ParticleEngineAccess engine) {
            Stash stash = new Stash(new IdentityHashMap<>(engine.wormholes$particles()), new ArrayList<>(engine.wormholes$emitters()),
                new ArrayList<>(engine.wormholes$pending()), new Object2IntOpenHashMap<>(engine.wormholes$counts()));
            engine.wormholes$particles().clear();
            engine.wormholes$emitters().clear();
            engine.wormholes$pending().clear();
            engine.wormholes$counts().clear();
            return stash;
        }

        private boolean empty() {
            for (ParticleGroup<?> group : groups.values()) {
                if (!group.isEmpty()) {
                    return false;
                }
            }
            return emitters.isEmpty() && pending.isEmpty();
        }

        private void restore(ParticleEngineAccess engine) {
            engine.wormholes$particles().putAll(groups);
            engine.wormholes$emitters().addAll(emitters);
            engine.wormholes$pending().addAll(pending);
            engine.wormholes$counts().putAll(counts);
        }
    }
}
