package art.arcane.wormholes.modded.client.render.stencil;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Vector4dc;
import org.joml.Vector4fc;

/**
 * One nested render of a destination world through a portal aperture. Accessor names match the 26.x class so
 * backend code keeps compiling; the visible-section lists are gone because 1.20.1 keeps them inside
 * {@link LevelRenderer} and the terrain backend swaps them itself.
 *
 * @param depth          stencil depth of this layer, 1 for a view seen from the main camera
 * @param view           the portal view being drawn
 * @param level          the destination level rendered inside the aperture
 * @param renderer       the renderer drawing {@code level}
 * @param shared         whether {@code renderer} is also the main renderer (same-level portals, mirrors)
 * @param camera         the inner camera for this layer
 * @param worldClipPlane destination clip plane in world space ({@code ax + by + cz + d >= 0} is kept)
 * @param clipSpacePlane the same plane in camera-relative view space, as uploaded to {@code WormholesClipPlane}
 */
public record PortalLayer(int depth, PortalView view, ClientLevel level, LevelRenderer renderer, boolean shared, FrameCamera camera,
                          Vector4dc worldClipPlane, Vector4fc clipSpacePlane) {
}
