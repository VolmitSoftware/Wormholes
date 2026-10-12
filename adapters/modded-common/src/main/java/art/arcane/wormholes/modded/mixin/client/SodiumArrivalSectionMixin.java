package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.sodium.SodiumArrivalSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkSortOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJob;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Pseudo
@Mixin(value = RenderSection.class, remap = false)
public abstract class SodiumArrivalSectionMixin implements SodiumArrivalSection {
    @Shadow @Final private List<ChunkJob> runningJobs;
    @Shadow private ChunkBuildOutput pendingBuildOutput;
    @Shadow private ChunkSortOutput pendingDynamicSortOutput;
    @Shadow private int pendingUpdateType;

    @Shadow
    public abstract boolean isBuilt();

    @Shadow
    public abstract boolean isDisposed();

    @Override
    public boolean wormholes$terrainComplete() {
        return !isDisposed() && isBuilt() && pendingUpdateType == 0 && !wormholes$buildsPending();
    }

    @Override
    public boolean wormholes$buildsPending() {
        return !runningJobs.isEmpty() || pendingBuildOutput != null || pendingDynamicSortOutput != null;
    }

    @Override
    public void wormholes$stealBuilds(ChunkBuilder builder) {
        for (ChunkJob job : runningJobs) {
            if (!job.isStarted()) {
                builder.tryStealTask(job);
            }
        }
    }
}
