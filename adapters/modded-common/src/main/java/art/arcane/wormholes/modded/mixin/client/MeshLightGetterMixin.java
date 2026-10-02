package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientMeshEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockAndLightGetter.class)
public interface MeshLightGetterMixin {
    @Inject(method = "getBrightness", at = @At("HEAD"), cancellable = true)
    private void wormholesMeshBrightness(LightLayer layer, BlockPos position, CallbackInfoReturnable<Integer> callback) {
        ClientMeshEntities scene = ClientMeshEntities.active(this);
        if (scene != null) {
            callback.setReturnValue(scene.brightness(layer, position));
        }
    }

    @Inject(method = "getRawBrightness", at = @At("HEAD"), cancellable = true)
    private void wormholesMeshRawBrightness(BlockPos position, int skyDarken, CallbackInfoReturnable<Integer> callback) {
        ClientMeshEntities scene = ClientMeshEntities.active(this);
        if (scene != null) {
            callback.setReturnValue(scene.rawBrightness(position, skyDarken));
        }
    }
}
