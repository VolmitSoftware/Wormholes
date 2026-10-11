package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.mixin.client.IrisPortalDhCompatAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalLodEventsAccess;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiFramebuffer;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiGenericObjectShaderProgram;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShadowCullingFrustum;
import com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector;

final class DistantHorizonsIrisState {
    private final boolean translucent = IrisPortalLodEventsAccess.wormholes$atTranslucent();
    private final int width = IrisPortalLodEventsAccess.wormholes$textureWidth();
    private final int height = IrisPortalLodEventsAccess.wormholes$textureHeight();
    private final boolean incompatible = IrisPortalDhCompatAccess.wormholes$lastIncompatible();
    private final IDhApiFramebuffer framebuffer = OverrideInjector.INSTANCE.get(IDhApiFramebuffer.class, 10);
    private final IDhApiGenericObjectShaderProgram generic = OverrideInjector.INSTANCE.get(IDhApiGenericObjectShaderProgram.class, 10);
    private final IDhApiShadowCullingFrustum frustum = OverrideInjector.INSTANCE.get(IDhApiShadowCullingFrustum.class, 10);

    boolean restored() {
        return translucent == IrisPortalLodEventsAccess.wormholes$atTranslucent()
            && width == IrisPortalLodEventsAccess.wormholes$textureWidth()
            && height == IrisPortalLodEventsAccess.wormholes$textureHeight()
            && incompatible == IrisPortalDhCompatAccess.wormholes$lastIncompatible()
            && framebuffer == OverrideInjector.INSTANCE.get(IDhApiFramebuffer.class, 10)
            && generic == OverrideInjector.INSTANCE.get(IDhApiGenericObjectShaderProgram.class, 10)
            && frustum == OverrideInjector.INSTANCE.get(IDhApiShadowCullingFrustum.class, 10);
    }
}
