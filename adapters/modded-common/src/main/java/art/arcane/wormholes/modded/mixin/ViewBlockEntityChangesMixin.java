package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftNetworkService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class ViewBlockEntityChangesMixin {
    @Inject(method = "setChanged(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("TAIL"))
    private static void wormholesViewMetadata(Level world, BlockPos position, BlockState state, CallbackInfo callback) {
        if (!(world instanceof ServerLevel serverWorld) || !serverWorld.getServer().isSameThread()) {
            return;
        }
        MinecraftNetworkService network = MinecraftNetworkService.forServer(serverWorld.getServer());
        if (network != null) {
            network.blockChanged(serverWorld, position);
        }
    }
}
