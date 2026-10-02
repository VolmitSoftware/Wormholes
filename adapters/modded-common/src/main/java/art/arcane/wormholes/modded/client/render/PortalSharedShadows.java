package art.arcane.wormholes.modded.client.render;

import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shadows.ShadowRenderTargets;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class PortalSharedShadows implements AutoCloseable {
    private static final ThreadLocal<PortalSharedShadows> CONSTRUCTING = new ThreadLocal<>();
    private static final Map<ShadowRenderTargets, PortalSharedShadows> OWNERS = new IdentityHashMap<>();

    private ShadowRenderTargets target;

    public static ShadowRenderTargets create(WorldRenderingPipeline pipeline, int resolution, PackShadowDirectives directives) {
        PortalSharedShadows shared = CONSTRUCTING.get();
        if (shared == null) {
            return new ShadowRenderTargets(pipeline, resolution, directives);
        }
        if (shared.target == null) {
            shared.target = new ShadowRenderTargets(pipeline, resolution, directives);
            OWNERS.put(shared.target, shared);
        } else {
            for (int index = 0; index < shared.target.getRenderTargetCount(); index++) {
                if (shared.target.isFlipped(index)) {
                    shared.target.flip(index);
                }
            }
        }
        return shared.target;
    }

    public static void destroy(ShadowRenderTargets target) {
        if (!OWNERS.containsKey(target)) {
            target.destroy();
        }
    }

    Construction constructing() {
        return new Construction(this);
    }

    @Override
    public void close() {
        if (target != null) {
            ShadowRenderTargets released = target;
            target = null;
            OWNERS.remove(released);
            released.destroy();
        }
    }

    final class Construction implements AutoCloseable {
        private final PortalSharedShadows previous;
        private final int firstFramebuffer;

        private Construction(PortalSharedShadows owner) {
            previous = CONSTRUCTING.get();
            firstFramebuffer = target == null ? 0 : ((PortalShadowTargets) target).wormholes$framebuffers().size();
            CONSTRUCTING.set(owner);
        }

        List<GlFramebuffer> framebuffers() {
            if (target == null) {
                return List.of();
            }
            List<GlFramebuffer> owned = ((PortalShadowTargets) target).wormholes$framebuffers();
            int first = firstFramebuffer == 0 ? 2 : firstFramebuffer;
            return new ArrayList<>(owned.subList(Math.min(first, owned.size()), owned.size()));
        }

        @Override
        public void close() {
            if (previous == null) {
                CONSTRUCTING.remove();
            } else {
                CONSTRUCTING.set(previous);
            }
        }
    }

    void release(List<GlFramebuffer> framebuffers) {
        if (target == null) {
            return;
        }
        List<GlFramebuffer> owned = ((PortalShadowTargets) target).wormholes$framebuffers();
        Throwable failure = null;
        for (GlFramebuffer framebuffer : framebuffers) {
            if (!owned.remove(framebuffer)) {
                continue;
            }
            try {
                framebuffer.destroy();
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
