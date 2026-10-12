package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

@Pseudo
@Mixin(targets = "art.arcane.wormholes.modded.client.render.stencil.PortalWorldRenderer", remap = false)
public abstract class CrossingCostPortalRenderMixin {
    @Unique
    private static Method automator$destination;
    @Unique
    private static boolean automator$destinationUnavailable;

    @Inject(method = "render(Lart/arcane/wormholes/modded/client/render/stencil/PortalView;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4f;IZZ)V",
        at = @At("HEAD"))
    private void automator$portalBegin(@Coerce Object view, CameraRenderState camera, Matrix4f projection, int depth, boolean mirrored,
                                      boolean outerMirrored, CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.PORTAL_RENDER, automator$dimension(view));
    }

    @Inject(method = "render(Lart/arcane/wormholes/modded/client/render/stencil/PortalView;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4f;IZZ)V",
        at = @At("RETURN"))
    private void automator$portalEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.PORTAL_RENDER);
    }

    @Unique
    private static String automator$dimension(Object view) {
        if (!automator$destinationUnavailable) {
            try {
                if (automator$destination == null) {
                    automator$destination = view.getClass().getMethod("destination");
                }
                Object destination = automator$destination.invoke(view);
                if (destination instanceof ClientLevel level) {
                    return level.dimension().identifier().toString();
                }
                automator$destinationUnavailable = true;
                CrossingCosts.target("portalDimension", "unavailable: destination is not a ClientLevel");
            } catch (ReflectiveOperationException | RuntimeException failure) {
                automator$destinationUnavailable = true;
                CrossingCosts.target("portalDimension", "unavailable: " + failure.getClass().getSimpleName());
            }
        }
        return "unavailable";
    }
}
