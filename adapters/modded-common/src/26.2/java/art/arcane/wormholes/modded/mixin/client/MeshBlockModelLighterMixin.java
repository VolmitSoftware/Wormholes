package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalTerrainLighting;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.block.BlockModelLighter;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockModelLighter.class)
public abstract class MeshBlockModelLighterMixin {
    @WrapOperation(method = "prepareQuadAmbientOcclusion", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;gray(F)I"))
    private int wormholes$separateAmbientOcclusion(float brightness, Operation<Integer> original) {
        return PortalTerrainLighting.current() == null ? original.call(brightness) : PortalTerrainLighting.ambientColor(brightness);
    }

    @ModifyExpressionValue(method = {"prepareQuadAmbientOcclusion", "prepareQuadFlat"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;cardinalLighting()Lnet/minecraft/world/level/CardinalLighting;"))
    private CardinalLighting wormholes$workerDirectionalShading(CardinalLighting lighting) {
        return PortalTerrainLighting.cardinalLighting(lighting);
    }
}
