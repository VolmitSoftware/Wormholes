package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalClipShaders;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.Std140Builder;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ProjectionMatrixBuffer.class)
public abstract class PortalClipProjectionMixin {
    @ModifyExpressionValue(method = {"<init>", "writeBuffer"}, at = @At(value = "FIELD",
        target = "Lcom/mojang/blaze3d/systems/RenderSystem;PROJECTION_MATRIX_UBO_SIZE:I"))
    private int wormholes$clipPlaneSize(int size) {
        return PortalClipShaders.PROJECTION_UBO_SIZE;
    }

    @WrapOperation(method = "writeBuffer", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putMat4f(Lorg/joml/Matrix4fc;)Lcom/mojang/blaze3d/buffers/Std140Builder;"))
    private Std140Builder wormholes$noClipPlane(Std140Builder builder, Matrix4fc projection, Operation<Std140Builder> original) {
        return original.call(builder, projection).putVec4(0.0F, 0.0F, 0.0F, 0.0F);
    }
}
