package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftNetworkService;
import art.arcane.wormholes.modded.ViewMutationAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

@Mixin(LevelChunk.class)
public abstract class ViewLevelChunkMutationMixin implements ViewMutationAccess.Chunk {
    @Shadow @Final private Level level;
    @Shadow private boolean loaded;
    @Unique private LevelChunkSection[] wormholes$boundSections;
    @Unique private int wormholes$mutationDepth;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void wormholesBindCreatedSections(CallbackInfo callback) {
        if (level instanceof ServerLevel) {
            wormholesBindSections(false);
        }
    }

    @Inject(method = "setLoaded", at = @At("TAIL"))
    private void wormholesBindLoadedSections(boolean active, CallbackInfo callback) {
        if (!(level instanceof ServerLevel)) {
            return;
        }
        if (active) {
            wormholesBindSections(false);
        } else if (wormholes$boundSections != null) {
            for (int index = 0; index < wormholes$boundSections.length; index++) {
                ((ViewMutationAccess.Section) wormholes$boundSections[index]).wormholesUnbind((LevelChunk) (Object) this);
            }
            wormholes$boundSections = null;
        }
    }

    @WrapMethod(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;")
    private BlockState wormholesAppliedBlock(BlockPos position, BlockState state, int flags, Operation<BlockState> original) {
        if (!wormholesMutationActive()) {
            return original.call(position, state, flags);
        }
        wormholes$mutationDepth++;
        BlockState previous;
        try {
            previous = original.call(position, state, flags);
        } finally {
            wormholes$mutationDepth--;
        }
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (previous != null && previous != chunk.getBlockState(position) && wormholesMutationActive()) {
            MinecraftNetworkService.forServer(((ServerLevel) level).getServer()).blockMutated((ServerLevel) level, position);
        }
        return previous;
    }

    @Inject(method = "markUnsaved", at = @At("TAIL"))
    private void wormholesAppliedReplacement(CallbackInfo callback) {
        if (wormholesMutationActive()) {
            wormholesBindSections(true);
        }
    }

    @Override
    public boolean wormholesMutationActive() {
        if (!loaded || !(level instanceof ServerLevel world) || !world.getServer().isSameThread()
            || world.getServer().isStopped() || MinecraftNetworkService.forServer(world.getServer()) == null
            || world.getServer().getLevel(world.dimension()) != world) {
            return false;
        }
        LevelChunk chunk = (LevelChunk) (Object) this;
        return world.getChunkSource().getChunkNow(chunk.getPos().x(), chunk.getPos().z()) == chunk;
    }

    @Override
    public boolean wormholesMutatingBlock() {
        return wormholes$mutationDepth != 0;
    }

    @Override
    public void wormholesBlockMutated(int section, int x, int y, int z) {
        if (!wormholesMutationActive() || wormholesMutatingBlock()) {
            return;
        }
        LevelChunk chunk = (LevelChunk) (Object) this;
        ServerLevel world = (ServerLevel) level;
        BlockPos position = new BlockPos((chunk.getPos().x() << 4) + x,
            (chunk.getSectionYFromSectionIndex(section) << 4) + y, (chunk.getPos().z() << 4) + z);
        MinecraftNetworkService.forServer(world.getServer()).blockMutated(world, position);
    }

    @Override
    public void wormholesColumnMutated() {
        if (wormholesMutationActive()) {
            LevelChunk chunk = (LevelChunk) (Object) this;
            ServerLevel world = (ServerLevel) level;
            MinecraftNetworkService.forServer(world.getServer()).columnMutated(world, chunk.getPos().x(), chunk.getPos().z());
        }
    }

    @Unique
    private void wormholesBindSections(boolean notify) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        LevelChunkSection[] sections = chunk.getSections();
        if (wormholes$boundSections == null) {
            wormholes$boundSections = Arrays.copyOf(sections, sections.length);
            for (int index = 0; index < sections.length; index++) {
                ((ViewMutationAccess.Section) sections[index]).wormholesBind(chunk, index);
            }
            return;
        }
        boolean changed = false;
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection previous = wormholes$boundSections[index];
            if (previous == sections[index]) {
                continue;
            }
            changed |= notify && !ViewMutationAccess.sameContents(previous, sections[index]);
            ((ViewMutationAccess.Section) previous).wormholesUnbind(chunk);
            wormholes$boundSections[index] = sections[index];
            ((ViewMutationAccess.Section) sections[index]).wormholesBind(chunk, index);
        }
        if (changed) {
            wormholesColumnMutated();
        }
    }
}
