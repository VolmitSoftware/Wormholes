package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalRegion;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(value = ChunkRenderList.class, remap = false)
public abstract class SodiumPortalRenderListMixin {
    @WrapOperation(method = "prepareForRender", at = @At(value = "INVOKE",
        target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;clearAllCachedBatches()V"))
    private void wormholes$clearOwnBatches(RenderRegion region, Operation<Void> original) {
        ((SodiumPortalRegion) region).wormholes$listChanged((ChunkRenderList) (Object) this);
    }
}
