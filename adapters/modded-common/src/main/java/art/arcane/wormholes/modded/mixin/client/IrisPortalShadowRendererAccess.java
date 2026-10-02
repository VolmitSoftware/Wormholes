package art.arcane.wormholes.modded.mixin.client;

import net.irisshaders.iris.shadows.ShadowCompositeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public interface IrisPortalShadowRendererAccess {
    @Accessor("compositeRenderer")
    ShadowCompositeRenderer wormholes$composite();

    @Invoker("generateMipmaps")
    void wormholes$generateMipmaps();
}
