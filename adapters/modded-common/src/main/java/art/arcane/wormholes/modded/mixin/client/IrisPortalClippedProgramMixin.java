package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalClippedProgram;
import art.arcane.wormholes.modded.client.render.PortalClipScope;
import art.arcane.wormholes.modded.client.render.PortalIrisClipping;
import art.arcane.wormholes.modded.client.render.PortalShaderContext;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;

@Pseudo
@Mixin(targets = {"net.irisshaders.iris.pipeline.programs.ExtendedShader",
    "net.irisshaders.iris.pipeline.programs.FallbackShader"}, remap = false)
public abstract class IrisPortalClippedProgramMixin implements PortalClippedProgram {
    @Unique
    private boolean wormholes$clippingAssigned;
    @Unique
    private int wormholes$clipDistance;
    @Unique
    private int wormholes$clipLocation;

    @Unique
    private Set<Integer> wormholes$existingDistances = Set.of();

    @Override
    public void wormholes$clipping(int program, int distance, Set<Integer> existingDistances) {
        int location = GlStateManager._glGetUniformLocation(program, PortalIrisClipping.UNIFORM);
        if (location < 0) {
            throw new IllegalStateException("Destination clipping uniform is not active");
        }
        wormholes$clipDistance = distance;
        wormholes$clipLocation = location;
        wormholes$existingDistances = Set.copyOf(existingDistances);
        wormholes$clippingAssigned = true;
    }

    @Inject(method = "iris$setupState", at = @At("RETURN"))
    private void wormholes$clip(List<BindGroupLayout.UniformDescription> samplers, CallbackInfo callback) {
        PortalClipScope clipping = PortalClipScope.current();
        if (clipping == null) {
            return;
        }
        if (!wormholes$clippingAssigned || PortalShaderContext.current() == null || !PortalShaderContext.drawing()) {
            clipping.select(-1, Set.of());
            return;
        }
        Vector4fc plane = clipping.plane();
        IrisRenderSystem.uniform4f(wormholes$clipLocation, plane.x(), plane.y(), plane.z(), plane.w());
        clipping.select(wormholes$clipDistance, wormholes$existingDistances);
    }
}
