package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

public final class IrisPipelineCostTap {
    private static final Map<IrisRenderingPipeline, Lifetime> LIFETIMES = new IdentityHashMap<>();
    private static boolean enabled;
    private static long creations;
    private static long drawCreations;
    private static long constructionNanos;
    private static long drawConstructionNanos;
    private static long destructions;
    private static long links;
    private static long drawLinks;
    private static long linkNanos;
    private static long drawLinkNanos;

    private IrisPipelineCostTap() {
    }

    public static void start() {
        LIFETIMES.clear();
        creations = 0L;
        drawCreations = 0L;
        constructionNanos = 0L;
        drawConstructionNanos = 0L;
        destructions = 0L;
        links = 0L;
        drawLinks = 0L;
        linkNanos = 0L;
        drawLinkNanos = 0L;
        enabled = true;
    }

    public static void stop() {
        enabled = false;
        LIFETIMES.clear();
    }

    public static boolean drawing() {
        PortalStencilRenderer renderer = PortalStencilRenderer.instance();
        return renderer.nested() && renderer.camera() != null;
    }

    public static void created(long nanos, boolean draw) {
        if (!enabled) {
            return;
        }
        creations++;
        constructionNanos += nanos;
        if (draw) {
            drawCreations++;
            drawConstructionNanos += nanos;
        }
    }

    public static void linked(long nanos, boolean draw) {
        if (!enabled) {
            return;
        }
        links++;
        linkNanos += nanos;
        if (draw) {
            drawLinks++;
            drawLinkNanos += nanos;
        }
    }

    public static void destroyedPipeline(IrisRenderingPipeline pipeline) {
        if (!enabled) {
            return;
        }
        Lifetime lifetime = LIFETIMES.computeIfAbsent(pipeline, key -> new Lifetime());
        if (lifetime.destroyed == 0L) {
            lifetime.destroyed = System.nanoTime();
            destructions++;
        }
    }

    public static boolean destroyed(IrisRenderingPipeline pipeline) {
        Lifetime lifetime = LIFETIMES.get(pipeline);
        return lifetime != null && lifetime.destroyed != 0L;
    }

    public static void rendered(IrisRenderingPipeline pipeline) {
        if (enabled) {
            LIFETIMES.computeIfAbsent(pipeline, key -> new Lifetime()).lastRendered = System.nanoTime();
        }
    }

    public static long releasedIdleNanos(IrisRenderingPipeline pipeline) {
        Lifetime lifetime = LIFETIMES.get(pipeline);
        if (lifetime == null || lifetime.lastRendered == 0L || lifetime.destroyed == 0L) {
            return -1L;
        }
        return lifetime.destroyed - lifetime.lastRendered;
    }

    public static Counts snapshot() {
        return new Counts(creations, drawCreations, creations - drawCreations, constructionNanos, drawConstructionNanos,
            constructionNanos - drawConstructionNanos, destructions, links, drawLinks, links - drawLinks, linkNanos,
            drawLinkNanos, linkNanos - drawLinkNanos);
    }

    public static Counts log(String phase, Counts before) {
        Counts delta = snapshot().minus(before);
        System.out.printf(Locale.ROOT,
            "Iris pipeline cost phase=%s creations=%d draw=%d preparation=%d constructionMs=%.3f drawMs=%.3f preparationMs=%.3f"
                + " links=%d drawLinks=%d preparationLinks=%d linkMs=%.3f drawLinkMs=%.3f preparationLinkMs=%.3f destructions=%d%n",
            phase, delta.creations(), delta.drawCreations(), delta.preparationCreations(), delta.constructionNanos() / 1_000_000.0D,
            delta.drawConstructionNanos() / 1_000_000.0D, delta.preparationConstructionNanos() / 1_000_000.0D,
            delta.links(), delta.drawLinks(), delta.preparationLinks(), delta.linkNanos() / 1_000_000.0D,
            delta.drawLinkNanos() / 1_000_000.0D, delta.preparationLinkNanos() / 1_000_000.0D, delta.destructions());
        return delta;
    }

    public record Counts(long creations, long drawCreations, long preparationCreations, long constructionNanos,
                         long drawConstructionNanos, long preparationConstructionNanos, long destructions,
                         long links, long drawLinks, long preparationLinks, long linkNanos, long drawLinkNanos,
                         long preparationLinkNanos) {
        public Counts minus(Counts before) {
            return new Counts(creations - before.creations, drawCreations - before.drawCreations,
                preparationCreations - before.preparationCreations, constructionNanos - before.constructionNanos,
                drawConstructionNanos - before.drawConstructionNanos, preparationConstructionNanos - before.preparationConstructionNanos,
                destructions - before.destructions, links - before.links, drawLinks - before.drawLinks,
                preparationLinks - before.preparationLinks, linkNanos - before.linkNanos,
                drawLinkNanos - before.drawLinkNanos, preparationLinkNanos - before.preparationLinkNanos);
        }
    }

    private static final class Lifetime {
        private long lastRendered;
        private long destroyed;
    }
}
