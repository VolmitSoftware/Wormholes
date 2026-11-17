package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientMeshEntities;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityRenderDispatcher.class)
public abstract class MeshEntityRendererMixin {
    @Redirect(method = "renderBlockShadow(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;DDDFF)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState wormholesMeshShadowBlock(ChunkAccess chunk, BlockPos position) {
        ClientMeshEntities scene = ClientMeshEntities.active();
        return scene == null ? chunk.getBlockState(position) : scene.blockState(position);
    }
}
