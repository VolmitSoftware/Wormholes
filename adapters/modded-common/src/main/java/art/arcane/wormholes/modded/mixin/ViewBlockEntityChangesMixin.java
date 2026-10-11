package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftNetworkService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class ViewBlockEntityChangesMixin {
    @Inject(method = "setChanged()V", at = @At("TAIL"))
    private void wormholesViewMetadata(CallbackInfo callback) {
        BlockEntity entity = (BlockEntity) (Object) this;
        if (!(entity.getLevel() instanceof ServerLevel serverWorld) || !serverWorld.getServer().isSameThread()
            || serverWorld.getServer().isStopped() || entity.isRemoved()) {
            return;
        }
        BlockPos position = entity.getBlockPos();
        LevelChunk chunk = serverWorld.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
        if (chunk == null || chunk.getBlockEntities().get(position) != entity) {
            return;
        }
        MinecraftNetworkService network = MinecraftNetworkService.forServer(serverWorld.getServer());
        if (network != null) {
            network.metadataChanged(serverWorld, position);
        }
    }
}
