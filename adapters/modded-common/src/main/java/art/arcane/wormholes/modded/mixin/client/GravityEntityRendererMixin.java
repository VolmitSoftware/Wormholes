package art.arcane.wormholes.modded.mixin.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.client.GravityRenderState;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class GravityEntityRendererMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void wormholes$extractGravity(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo callback) {
        GravityFrame frame = MinecraftGravity.frame(entity);
        ((GravityRenderState) state).wormholes$gravity(frame);
        if (!frame.equals(GravityFrame.WORLD) && state.nameTagAttachment != null) {
            state.nameTagAttachment = MinecraftGravity.world(frame, state.nameTagAttachment);
        }
    }
}
