package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalDrawScope;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

@Mixin(CommandEncoder.class)
public abstract class PortalDrawPassMixin {
    @WrapMethod(method = "createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;")
    private RenderPass wormholes$destinationPass(Supplier<String> label, GpuTextureView color, Optional<Vector4fc> clear,
                                                 GpuTextureView depth, OptionalDouble clearDepth, Operation<RenderPass> original) {
        PortalDrawScope scope = PortalDrawScope.current();
        if (scope == null) {
            return original.call(label, color, clear, depth, clearDepth);
        }
        return ((CommandEncoder) (Object) this).createRenderPass(label, scope.color(), clear, scope.depth(), clearDepth, scope.area());
    }
}
