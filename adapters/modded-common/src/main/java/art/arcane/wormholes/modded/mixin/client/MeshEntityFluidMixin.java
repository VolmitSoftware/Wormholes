package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientMeshEntities;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityFluidInteraction.class)
public abstract class MeshEntityFluidMixin {
    @Inject(method = "hasFluidAndLoaded", at = @At("HEAD"), cancellable = true)
    private static void wormholesSnapshotFluid(Level level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                               CallbackInfoReturnable<Boolean> callback) {
        if (ClientMeshEntities.active(level) != null) {
            callback.setReturnValue(true);
        }
    }
}
