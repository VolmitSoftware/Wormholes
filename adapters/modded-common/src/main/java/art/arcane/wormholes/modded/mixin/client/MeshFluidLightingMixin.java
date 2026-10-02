package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalTerrainLighting;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FluidRenderer.class)
public abstract class MeshFluidLightingMixin {
    @ModifyExpressionValue(method = "tesselate", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;cardinalLighting()Lnet/minecraft/world/level/CardinalLighting;"))
    private CardinalLighting wormholes$workerDirectionalShading(CardinalLighting lighting) {
        return PortalTerrainLighting.cardinalLighting(lighting);
    }
}
