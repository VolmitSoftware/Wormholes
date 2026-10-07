package art.arcane.wormholes.modded.mixin.client;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TrackingEmitter;
import net.minecraft.core.particles.ParticleLimit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.Queue;

@Mixin(ParticleEngine.class)
public interface ParticleEngineAccess {
    @Accessor("level")
    ClientLevel wormholes$level();

    @Accessor("level")
    void wormholes$level(ClientLevel level);

    @Accessor("particles")
    Map<ParticleRenderType, ParticleGroup<?>> wormholes$particles();

    @Accessor("trackingEmitters")
    Queue<TrackingEmitter> wormholes$emitters();

    @Accessor("particlesToAdd")
    Queue<Particle> wormholes$pending();

    @Accessor("trackedParticleCounts")
    Object2IntOpenHashMap<ParticleLimit> wormholes$counts();
}
