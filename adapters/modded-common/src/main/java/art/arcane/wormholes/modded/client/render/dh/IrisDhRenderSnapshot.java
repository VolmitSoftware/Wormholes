package art.arcane.wormholes.modded.client.render.dh;

import art.arcane.wormholes.modded.mixin.client.IrisPortalDhCompatAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalLodEventsAccess;
import com.seibel.distanthorizons.api.interfaces.override.IDhApiOverrideable;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiFramebuffer;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiGenericObjectShaderProgram;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShadowCullingFrustum;
import com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector;

final class IrisDhRenderSnapshot {
    private static final int IRIS_OVERRIDE_PRIORITY = 10;

    private final boolean translucent = IrisPortalLodEventsAccess.wormholes$atTranslucent();
    private final int width = IrisPortalLodEventsAccess.wormholes$textureWidth();
    private final int height = IrisPortalLodEventsAccess.wormholes$textureHeight();
    private final boolean incompatible = IrisPortalDhCompatAccess.wormholes$lastIncompatible();
    private final OverrideSlot<IDhApiFramebuffer> framebuffer = new OverrideSlot<>(IDhApiFramebuffer.class);
    private final OverrideSlot<IDhApiGenericObjectShaderProgram> generic = new OverrideSlot<>(IDhApiGenericObjectShaderProgram.class);
    private final OverrideSlot<IDhApiShadowCullingFrustum> frustum = new OverrideSlot<>(IDhApiShadowCullingFrustum.class);

    void enter() {
        framebuffer.removeParent();
        generic.removeParent();
        frustum.removeParent();
        IrisPortalLodEventsAccess.wormholes$atTranslucent(false);
    }

    void restore() {
        try {
            framebuffer.restore();
        } finally {
            try {
                generic.restore();
            } finally {
                try {
                    frustum.restore();
                } finally {
                    IrisPortalLodEventsAccess.wormholes$atTranslucent(translucent);
                    IrisPortalLodEventsAccess.wormholes$textureWidth(width);
                    IrisPortalLodEventsAccess.wormholes$textureHeight(height);
                    IrisPortalDhCompatAccess.wormholes$lastIncompatible(incompatible);
                }
            }
        }
    }

    private static final class OverrideSlot<T extends IDhApiOverrideable> {
        private final Class<T> type;
        private final T previous;

        private OverrideSlot(Class<T> type) {
            this.type = type;
            previous = OverrideInjector.INSTANCE.get(type, IRIS_OVERRIDE_PRIORITY);
        }

        private void removeParent() {
            if (previous != null && previous.getClass().getName().startsWith("net.irisshaders.iris.")) {
                OverrideInjector.INSTANCE.unbind(type, previous);
            }
        }

        private void restore() {
            T current = OverrideInjector.INSTANCE.get(type, IRIS_OVERRIDE_PRIORITY);
            if (current == previous) {
                return;
            }
            if (current != null) {
                OverrideInjector.INSTANCE.unbind(type, current);
            }
            if (previous != null) {
                OverrideInjector.INSTANCE.bind(type, previous);
            }
        }
    }
}
