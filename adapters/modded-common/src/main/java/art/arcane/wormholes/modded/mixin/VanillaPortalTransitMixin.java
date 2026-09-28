package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftPortalConstruction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({NetherPortalBlock.class, EndPortalBlock.class})
public abstract class VanillaPortalTransitMixin {
    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void wormholes$managedTransit(BlockState state, Level level, BlockPos position, Entity entity,
                                          InsideBlockEffectApplier effects, boolean precise, CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel) {
            MinecraftPortalConstruction construction = MinecraftPortalConstruction.forServer(serverLevel.getServer());
            if (construction != null && construction.vanilla().suppresses(serverLevel, position)) {
                callback.cancel();
            }
        }
    }
}
