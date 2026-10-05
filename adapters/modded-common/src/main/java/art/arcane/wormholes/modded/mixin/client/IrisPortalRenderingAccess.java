package art.arcane.wormholes.modded.mixin.client;

import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public interface IrisPortalRenderingAccess {
    @Accessor("shadowRenderer")
    ShadowRenderer wormholes$shadowRenderer();

    @Accessor("prepareRenderer")
    CompositeRenderer wormholes$prepareRenderer();

    @Accessor("initializedBlockIds")
    void wormholes$initializedBlockIds(boolean initialized);

    @Accessor("renderTargets")
    RenderTargets wormholes$renderTargets();

    @Accessor("shadowRenderTargets")
    ShadowRenderTargets wormholes$shadowTargets();
    @Accessor("isRenderingWorld")
    boolean wormholes$renderingWorld();

    @Accessor("isRenderingWorld")
    void wormholes$renderingWorld(boolean value);

    @Accessor("isMainBound")
    boolean wormholes$mainBound();

    @Accessor("isMainBound")
    void wormholes$mainBound(boolean value);
    @Accessor("phase")
    WorldRenderingPhase wormholes$phase();
}
