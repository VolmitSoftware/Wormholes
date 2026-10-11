package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.ViewMutationAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LevelChunkSection.class)
public abstract class ViewSectionMutationMixin implements ViewMutationAccess.Section {
    @Unique private LevelChunk wormholes$owner;
    @Unique private int wormholes$index;
    @Unique private int wormholes$setterDepth;
    @Unique private int wormholes$replacementDepth;

    @Override
    public void wormholesBind(LevelChunk chunk, int index) {
        wormholes$owner = chunk;
        wormholes$index = index;
        LevelChunkSection section = (LevelChunkSection) (Object) this;
        ((ViewMutationAccess.Palette) section.getStates()).wormholesBind(chunk == null ? null : section);
        if (section.getBiomes() instanceof ViewMutationAccess.Palette palette) {
            palette.wormholesBind(chunk == null ? null : section);
        }
    }

    @WrapMethod(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;")
    private BlockState wormholesAppliedSectionBlock(int x, int y, int z, BlockState state, boolean acquire, Operation<BlockState> original) {
        wormholes$setterDepth++;
        BlockState previous;
        try {
            previous = original.call(x, y, z, state, acquire);
        } finally {
            wormholes$setterDepth--;
        }
        if (wormholesCurrent() && previous != ((LevelChunkSection) (Object) this).getBlockState(x, y, z)) {
            ((ViewMutationAccess.Chunk) wormholes$owner).wormholesBlockMutated(wormholes$index, x, y, z);
        }
        return previous;
    }

    @WrapMethod(method = {"read", "readBiomes"})
    private void wormholesAppliedRead(FriendlyByteBuf buffer, Operation<Void> original) {
        LevelChunkSection before = wormholesBeforeReplacement();
        Throwable failure = null;
        try {
            original.call(buffer);
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            wormholesAfterReplacement(before, failure);
        }
    }

    @WrapMethod(method = "fillBiomesFromNoise(Lnet/minecraft/world/level/biome/BiomeResolver;III)V", require = 0)
    private void wormholesAppliedBiomes(BiomeResolver resolver, int x, int y, int z, Operation<Void> original) {
        LevelChunkSection before = wormholesBeforeReplacement();
        Throwable failure = null;
        try {
            original.call(resolver, x, y, z);
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            wormholesAfterReplacement(before, failure);
        }
    }

    @WrapMethod(method = "fillBiomesFromNoise(Lnet/minecraft/world/level/biome/BiomeResolver;Lnet/minecraft/world/level/biome/Climate$Sampler;III)V", require = 0)
    private void wormholesAppliedSampledBiomes(BiomeResolver resolver, Climate.Sampler sampler, int x, int y, int z, Operation<Void> original) {
        LevelChunkSection before = wormholesBeforeReplacement();
        Throwable failure = null;
        try {
            original.call(resolver, sampler, x, y, z);
        } catch (Throwable error) {
            failure = error;
            throw error;
        } finally {
            wormholesAfterReplacement(before, failure);
        }
    }

    @Override
    public void wormholesUnbind(LevelChunk chunk) {
        if (wormholes$owner == chunk) {
            wormholesBind(null, -1);
        }
    }

    @Override
    public boolean wormholesPaletteActive() {
        return wormholesCurrent() && wormholes$replacementDepth == 0 && wormholes$setterDepth == 0
            && !((ViewMutationAccess.Chunk) wormholes$owner).wormholesMutatingBlock();
    }

    @Override
    public void wormholesPaletteMutated(Object palette, int x, int y, int z) {
        if (wormholes$setterDepth != 0 || wormholes$replacementDepth != 0 || !wormholesCurrent()) {
            return;
        }
        LevelChunkSection section = (LevelChunkSection) (Object) this;
        if (palette == section.getStates()) {
            ((ViewMutationAccess.Chunk) wormholes$owner).wormholesBlockMutated(wormholes$index, x, y, z);
        } else if (palette == section.getBiomes()) {
            ((ViewMutationAccess.Chunk) wormholes$owner).wormholesColumnMutated();
        }
    }

    @Override
    public void wormholesPaletteReplaced(Object palette) {
        if (wormholes$replacementDepth == 0 && wormholesCurrent()) {
            LevelChunkSection section = (LevelChunkSection) (Object) this;
            if (palette == section.getStates() || palette == section.getBiomes()) {
                ((ViewMutationAccess.Chunk) wormholes$owner).wormholesColumnMutated();
            }
        }
    }

    @Unique
    private LevelChunkSection wormholesBeforeReplacement() {
        LevelChunkSection before = wormholes$replacementDepth == 0 && wormholesCurrent() ? ((LevelChunkSection) (Object) this).copy() : null;
        wormholes$replacementDepth++;
        return before;
    }

    @Unique
    private void wormholesAfterReplacement(LevelChunkSection before, Throwable failure) {
        wormholes$replacementDepth--;
        try {
            wormholesBind(wormholes$owner, wormholes$index);
            if (before != null && wormholesCurrent() && !ViewMutationAccess.sameContents(before, (LevelChunkSection) (Object) this)) {
                ((ViewMutationAccess.Chunk) wormholes$owner).wormholesColumnMutated();
            }
        } catch (Throwable cleanup) {
            if (failure == null) {
                throw cleanup;
            }
            if (failure != cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    @Unique
    private boolean wormholesCurrent() {
        return wormholes$owner != null && ((ViewMutationAccess.Chunk) wormholes$owner).wormholesMutationActive()
            && wormholes$owner.getSections()[wormholes$index] == (Object) this;
    }
}
