package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public interface IrisPortalHandFrameAccess {
    @Accessor("context")
    FeatureFrameContext wormholes$context();
}
