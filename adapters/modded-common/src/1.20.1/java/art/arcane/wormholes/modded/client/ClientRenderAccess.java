package art.arcane.wormholes.modded.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;

/**
 * 1.20.1 view of the client render objects. The 26.x render-state, level-load tracker and block-entity extraction
 * accessors have no 1.20.1 counterpart and are left out.
 */
public final class ClientRenderAccess {
    private ClientRenderAccess() {
    }

    /** The main framebuffer that the world and the GUI draw into. */
    public static RenderTarget mainTarget(Minecraft minecraft) {
        return minecraft.getMainRenderTarget();
    }

    /** The camera the main view renders from. */
    public static Camera mainCamera(Minecraft minecraft) {
        return minecraft.gameRenderer.getMainCamera();
    }

    /** Whether the server has assigned this id; 1.20.1 has no reserved invalid id, so every id counts. */
    public static boolean assignedEntityId(int entityId) {
        return true;
    }

    /** 1.20.1 renders through OpenGL only. */
    public static String backendName() {
        return "OpenGL";
    }

    /** 1.20.1 uses the standard depth direction (near 0, far 1 after the viewport transform). */
    public static boolean reversedDepth() {
        return false;
    }

    /** 1.20.1 keeps OpenGL's -1..1 clip-space depth range. */
    public static boolean zeroToOneDepth() {
        return false;
    }
}
