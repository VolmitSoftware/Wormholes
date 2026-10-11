package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.PortalEntityRenderTap;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class PortalEntityDispatcherTap {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void wormholesTest$identity(Entity entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> callback) {
        PortalEntityRenderTap.identify(entity, callback.getReturnValue());
    }

    @Inject(method = "submit", at = @At("TAIL"))
    private void wormholesTest$submitted(EntityRenderState state, CameraRenderState camera, double x, double y, double z,
                                        PoseStack matrices, SubmitNodeCollector submits, CallbackInfo callback) {
        PortalEntityRenderTap.submitted(state);
    }
}
