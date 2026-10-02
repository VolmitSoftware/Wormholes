package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalFeatureRenderer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(StagedVertexBuffer.class)
public abstract class MeshFeatureWindingMixin {
    @Redirect(method = "finishLastVertexBuilder", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;build()Lcom/mojang/blaze3d/vertex/MeshData;"))
    private MeshData wormholesReflectFeatureWinding(BufferBuilder builder) {
        MeshData mesh = builder.build();
        PortalFeatureRenderer.correctWinding((StagedVertexBuffer) (Object) this, mesh);
        return mesh;
    }
}
