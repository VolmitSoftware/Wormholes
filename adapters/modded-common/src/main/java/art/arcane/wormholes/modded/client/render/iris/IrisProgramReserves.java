package art.arcane.wormholes.modded.client.render.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.lwjgl.opengl.GL20C;
import org.slf4j.Logger;

import java.util.Set;

public final class IrisProgramReserves {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long LOADING_BUDGET_NANOS = 200_000_000L;
    private static final IrisProgramReserve RESERVE = new IrisProgramReserve(new IrisProgramReserve.Options(
        inputs -> IrisProgramLinker.link(inputs), GL20C::glDeleteProgram, System::nanoTime));
    private static boolean homeResolved;
    private static IrisProgramReserve.Statistics loading;

    private IrisProgramReserves() {
    }

    public static void step() {
        RenderSystem.assertOnRenderThread();
        if (!Iris.isPackInUseQuick()) {
            return;
        }
        if (!homeResolved) {
            resolveHome();
        }
        try {
            RESERVE.step();
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to reserve an Iris portal program; portal pipelines that need it link it while they are built", failure);
        }
    }

    public static void clear() {
        RenderSystem.assertOnRenderThread();
        homeResolved = false;
        loading = null;
        RESERVE.clear();
    }

    public static Diagnostics diagnostics() {
        IrisProgramReserve.Statistics statistics = RESERVE.statistics();
        return new Diagnostics(statistics.linked(), statistics.handed(), statistics.misses(), statistics.deleted(), statistics.failures(),
            statistics.linkNanos(), statistics.reserved(), statistics.deficits(), statistics.recipes());
    }

    public static Recording record(NamespacedId dimension) {
        return open(text(dimension), false);
    }

    public static int shader(String name, String vertex, String geometry, String tessControl, String tessEval, String fragment, Object format,
                             boolean fallback) {
        return RESERVE.take(IrisProgramReserve.Inputs.shader(name, vertex, geometry, tessControl, tessEval, fragment, format, fallback));
    }

    public static int program(String name, String vertex, String geometry, String fragment, Set<Integer> units) {
        return RESERVE.take(IrisProgramReserve.Inputs.program(name, vertex, geometry, fragment, units));
    }

    public static int compute(String name, String source, Set<Integer> units) {
        return RESERVE.take(IrisProgramReserve.Inputs.compute(name, source, units));
    }

    public static int sodium(String name, String vertex, String geometry, String tessControl, String tessEval, String fragment) {
        return RESERVE.take(IrisProgramReserve.Inputs.sodium(name, vertex, geometry, tessControl, tessEval, fragment));
    }

    static boolean admits(String dimension) {
        RenderSystem.assertOnRenderThread();
        return RESERVE.admits(dimension);
    }

    static Recording consume(String dimension) {
        return open(dimension, true);
    }

    static boolean fill() {
        RenderSystem.assertOnRenderThread();
        resolveHome();
        if (!homeResolved) {
            return true;
        }
        if (loading == null) {
            loading = RESERVE.statistics();
        }
        boolean full;
        try {
            full = RESERVE.fill(LOADING_BUDGET_NANOS);
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to reserve an Iris portal program while the level loads", failure);
            full = false;
        }
        if (full) {
            IrisProgramReserve.Statistics reserved = RESERVE.statistics();
            long linked = reserved.linked() - loading.linked();
            if (linked > 0L) {
                LOGGER.info("Iris portal program reserve linked {} programs ({} sets for {}) in {} ms", linked, IrisProgramReserve.HOME_SETS,
                    text(Iris.getCurrentDimension()), (reserved.linkNanos() - loading.linkNanos()) / 1_000_000L);
            }
            loading = null;
        }
        return full;
    }

    private static Recording open(String dimension, boolean consuming) {
        RenderSystem.assertOnRenderThread();
        RESERVE.begin(dimension, consuming);
        return new Recording();
    }

    private static void resolveHome() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        homeResolved = true;
        RESERVE.home(text(Iris.getCurrentDimension()));
    }

    private static String text(NamespacedId dimension) {
        return dimension.getNamespace() + ":" + dimension.getName();
    }

    public record Diagnostics(long linked, long handed, long misses, long deleted, long failures, long linkNanos, int reserved, int deficits,
                              int recipes) {
    }

    public static final class Recording implements AutoCloseable {
        private boolean completed;

        private Recording() {
        }

        public WorldRenderingPipeline completed(WorldRenderingPipeline pipeline) {
            completed = pipeline instanceof IrisRenderingPipeline;
            return pipeline;
        }

        void complete() {
            completed = true;
        }

        @Override
        public void close() {
            RESERVE.end(completed);
        }
    }
}
