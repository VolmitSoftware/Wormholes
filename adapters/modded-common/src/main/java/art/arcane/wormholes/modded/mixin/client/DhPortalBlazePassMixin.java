package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.dh.DhBlazeClipPrograms;
import art.arcane.wormholes.modded.client.render.dh.DhBlazeClipTransformation;
import art.arcane.wormholes.modded.client.render.dh.DhPortalRenderContext;
import art.arcane.wormholes.modded.client.render.stencil.PortalClipShaders;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.resources.Identifier;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper", remap = false)
public abstract class DhPortalBlazePassMixin {
    @Shadow
    @Final
    private static CommandEncoder COMMAND_ENCODER;

    @Shadow
    @Final
    private RenderPass renderPass;

    @Unique
    private int wormholes$previousDistances;

    @Unique
    private boolean wormholes$clipping;

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"))
    private RenderPass wormholes$portalCopyStencil(CommandEncoder encoder, Supplier<String> label, GpuTextureView color,
                                                   Optional<Vector4fc> clear, GpuTextureView depth, OptionalDouble clearDepth,
                                                   Operation<RenderPass> original) {
        return original.call(encoder, label, color, clear, DhPortalRenderContext.copyDepth(color, depth), clearDepth);
    }

    @Inject(method = "setPipeline", at = @At("RETURN"))
    private void wormholes$clipBlaze(RenderPipeline pipeline, CallbackInfo callback) {
        wormholes$restore();
        Identifier vertex = pipeline.getShaders().get(ShaderType.VERTEX);
        if (vertex != null && "distanthorizons".equals(vertex.getNamespace()) && wormholes$fullscreen(vertex.getPath())
            && PortalClipShaders.openGl()) {
            wormholes$previousDistances = DhBlazeClipPrograms.suspend();
            wormholes$clipping = true;
            return;
        }
        if (vertex == null || !"distanthorizons".equals(vertex.getNamespace()) || !DhBlazeClipTransformation.owns(vertex.getPath())
            || !PortalClipShaders.openGl()) {
            return;
        }
        renderPass.setUniform(DhBlazeClipTransformation.BLOCK,
            DhBlazeClipPrograms.upload(DhPortalClientApiAccess.wormholes$renderParams(), COMMAND_ENCODER));
        wormholes$previousDistances = DhBlazeClipPrograms.enable();
        wormholes$clipping = true;
    }

    @WrapMethod(method = "close")
    private void wormholes$closeBlaze(Operation<Void> original) {
        try {
            original.call();
        } finally {
            wormholes$restore();
        }
    }

    @Unique
    private static boolean wormholes$fullscreen(String vertex) {
        return switch (vertex) {
            case "copy/blaze/vert", "apply/blaze/vert", "fog/blaze/vert", "ssao/blaze/vert",
                "antialias/blaze/vert", "fade/blaze/vert" -> true;
            default -> false;
        };
    }

    @Unique
    private void wormholes$restore() {
        if (wormholes$clipping) {
            DhBlazeClipPrograms.restore(wormholes$previousDistances);
            wormholes$clipping = false;
        }
    }
}
