package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisProgramReserves;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class IrisPortalProgramReserveMixin {
    @Inject(method = "render", at = @At("RETURN"))
    private void wormholes$reservePrograms(CallbackInfo callback) {
        IrisProgramReserves.step();
    }
}
