package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientMeshEntities;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.blockentity.state.EndPortalRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractEndPortalRenderer.class)
public abstract class EndPortalSurfaceMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/level/block/entity/TheEndPortalBlockEntity;Lnet/minecraft/client/renderer/blockentity/state/EndPortalRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V", at = @At("TAIL"))
    private void wormholes$managedSurface(TheEndPortalBlockEntity blockEntity, EndPortalRenderState state, float partialTicks,
                                         Vec3 cameraPosition, ModelFeatureRenderer.CrumblingOverlay breakProgress, CallbackInfo callback) {
        if (ClientMeshEntities.active() == null && blockEntity.getLevel() == Minecraft.getInstance().level
            && blockEntity.getBlockState().is(Blocks.END_PORTAL)
            && ClientPortalRenderer.instance().coversEndPortalSurface(blockEntity.getBlockPos())) {
            state.facesToShow.clear();
        }
    }
}
