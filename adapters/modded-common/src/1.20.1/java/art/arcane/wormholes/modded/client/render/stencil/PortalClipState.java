package art.arcane.wormholes.modded.client.render.stencil;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * The clip plane every patched shader reads from its {@code WormholesClipPlane} uniform. Render thread only.
 * Outside a portal layer the plane is {@link #NONE}, which keeps every vertex.
 */
public final class PortalClipState {
    /** A plane that keeps everything: {@code 0x + 0y + 0z + 1 >= 0}. */
    public static final Vector4fc NONE = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);

    private static Vector4fc current = NONE;

    private PortalClipState() {
    }

    /** Sets the camera-relative view-space plane, or clears it with {@code null}. */
    public static void set(@Nullable Vector4fc viewPlane) {
        current = viewPlane == null ? NONE : viewPlane;
    }

    /** The active plane, {@link #NONE} when no layer is clipping. */
    public static Vector4fc current() {
        return current;
    }

    /** Whether a layer plane is active. */
    public static boolean active() {
        return current != NONE;
    }
}
