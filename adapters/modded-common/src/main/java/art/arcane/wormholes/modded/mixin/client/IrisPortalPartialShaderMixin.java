package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisShaderStages;
import art.arcane.wormholes.modded.client.render.PortalShaderStageDiscard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.PartialShader", remap = false)
public abstract class IrisPortalPartialShaderMixin implements PortalShaderStageDiscard {
    @Shadow
    @Final
    private int vertexS;
    @Shadow
    @Final
    private int fragS;
    @Shadow
    @Final
    private int geometryS;
    @Shadow
    @Final
    private int tessContS;
    @Shadow
    @Final
    private int tessEvalS;
    @Shadow
    private boolean hasUnbound;
    @Unique
    private boolean wormholes$discarded;

    @Override
    public void wormholes$discardStages() {
        if (hasUnbound || wormholes$discarded) {
            return;
        }
        wormholes$discarded = true;
        PortalIrisShaderStages.discardOwned(vertexS);
        PortalIrisShaderStages.discardOwned(fragS);
        PortalIrisShaderStages.discardOwned(geometryS);
        PortalIrisShaderStages.discardOwned(tessContS);
        PortalIrisShaderStages.discardOwned(tessEvalS);
    }

    @WrapOperation(method = "detachIfValid", at = @At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;glDeleteShader(I)V"))
    private static void wormholes$delete(int handle, Operation<Void> original) {
        PortalIrisShaderStages.delete(handle);
    }
}
