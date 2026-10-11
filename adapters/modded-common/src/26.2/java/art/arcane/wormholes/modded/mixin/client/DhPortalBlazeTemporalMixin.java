package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.dh.DhBlazeTemporalViews;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.seibel.distanthorizons.core.render.RenderParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.postProcessing.BlazeDhTaaRenderer", remap = false)
public abstract class DhPortalBlazeTemporalMixin {
    @WrapMethod(method = "render")
    private void wormholes$cameraHistory(RenderParams parameters, Operation<Void> original) {
        try (DhBlazeTemporalViews.Scope scope = DhBlazeTemporalViews.open(parameters, (DhPortalBlazeTemporalAccess) this)) {
            original.call(parameters);
        }
    }
}
