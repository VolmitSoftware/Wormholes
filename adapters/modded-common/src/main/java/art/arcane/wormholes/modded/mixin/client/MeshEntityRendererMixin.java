package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientMeshEntities;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityRenderer.class)
public abstract class MeshEntityRendererMixin {
    @Redirect(method = "extractShadowPiece", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState wormholesMeshShadowBlock(ChunkAccess chunk, BlockPos position) {
        ClientMeshEntities scene = ClientMeshEntities.active();
        return scene == null ? chunk.getBlockState(position) : scene.blockState(position);
    }
}
