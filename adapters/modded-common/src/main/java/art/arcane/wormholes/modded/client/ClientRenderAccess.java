package art.arcane.wormholes.modded.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ClientRenderAccess {
    private ClientRenderAccess() {
    }

    public static RenderTarget mainTarget(Minecraft minecraft) {
        return minecraft.gameRenderer.mainRenderTarget();
    }

    public static Camera mainCamera(Minecraft minecraft) {
        return minecraft.gameRenderer.mainCamera();
    }

    public static GameRenderState gameState(Minecraft minecraft) {
        return minecraft.gameRenderer.gameRenderState();
    }

    public static boolean assignedEntityId(int entityId) {
        return entityId != Entity.INVALID_ENTITY_ID;
    }

    public static <E extends BlockEntity, S extends BlockEntityRenderState> S extractBlockEntity(BlockEntityRenderDispatcher dispatcher, E entity,
                                                                                               float partialTick, boolean offScreen) {
        return dispatcher.tryExtractRenderState(entity, partialTick, null, offScreen);
    }

    public static String backendName() {
        return RenderSystem.tryGetDevice() == null ? null : RenderSystem.tryGetDevice().getDeviceInfo().backendName();
    }

    public static boolean reversedDepth() {
        return true;
    }

    public static boolean zeroToOneDepth() {
        return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
    }
}
