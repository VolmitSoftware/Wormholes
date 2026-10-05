package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FeatureRenderDispatcher.class)
public interface IrisPortalHandBuffersAccess {
    @Accessor("preparedFrame")
    FeatureRenderDispatcher.PreparedFrame wormholes$frame();
}
