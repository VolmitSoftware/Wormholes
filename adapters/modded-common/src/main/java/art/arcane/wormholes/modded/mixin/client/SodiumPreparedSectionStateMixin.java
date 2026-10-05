package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalSodiumSectionState;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkSortOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJob;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(value = RenderSection.class, remap = false)
public abstract class SodiumPreparedSectionStateMixin implements PortalSodiumSectionState {
    @Shadow private int pendingUpdateType;
    @Shadow @Final private List<ChunkJob> runningJobs;
    @Shadow private ChunkBuildOutput pendingBuildOutput;
    @Shadow private ChunkSortOutput pendingDynamicSortOutput;

    @Override
    public boolean wormholes$buildSettled() {
        return !ChunkUpdateTypes.isRebuild(pendingUpdateType) && !ChunkUpdateTypes.isSort(pendingUpdateType)
            && runningJobs.isEmpty() && pendingBuildOutput == null && pendingDynamicSortOutput == null;
    }
}
