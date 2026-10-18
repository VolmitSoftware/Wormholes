package art.arcane.wormholes.modded.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ClientRenderAccess {
    private ClientRenderAccess() {
    }

    public static RenderTarget mainTarget(Minecraft minecraft) {
        return minecraft.getMainRenderTarget();
    }

    public static Camera mainCamera(Minecraft minecraft) {
        return minecraft.gameRenderer.getMainCamera();
    }

    public static GameRenderState gameState(Minecraft minecraft) {
        return minecraft.gameRenderer.getGameRenderState();
    }

    public static void finishLevelLoading(Minecraft minecraft, LevelLoadTracker tracker) {
        if (tracker == null) {
            return;
        }
        tracker.loadingPacketsReceived();
        tracker.tickClientLoad();
        if (tracker.isLevelReady() && minecraft.screen instanceof LevelLoadingScreen screen) {
            screen.onClose();
        }
    }

    public static boolean assignedEntityId(int entityId) {
        return entityId != 0;
    }

    public static <E extends BlockEntity, S extends BlockEntityRenderState> S extractBlockEntity(BlockEntityRenderDispatcher dispatcher, E entity,
                                                                                               float partialTick, boolean offScreen) {
        return dispatcher.tryExtractRenderState(entity, partialTick, null);
    }

    public static String backendName() {
        return RenderSystem.tryGetDevice() == null ? null : RenderSystem.tryGetDevice().getBackendName();
    }

    public static boolean reversedDepth() {
        return false;
    }

    public static boolean zeroToOneDepth() {
        return RenderSystem.getDevice().isZZeroToOne();
    }
}
