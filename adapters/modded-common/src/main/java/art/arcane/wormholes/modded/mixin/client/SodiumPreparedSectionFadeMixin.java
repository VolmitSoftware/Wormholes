package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderRegionManager.class)
public abstract class SodiumPreparedSectionFadeMixin {
    @WrapOperation(method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
        at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;consumeFade()Z"))
    private boolean wormholesPreparedSectionFade(RenderSection section, Operation<Boolean> original) {
        boolean fade = original.call(section);
        return fade && !ClientSodiumTerrain.prewarming() && !ClientPortalRenderer.instance().coversMainSection(
            SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
    }
}
