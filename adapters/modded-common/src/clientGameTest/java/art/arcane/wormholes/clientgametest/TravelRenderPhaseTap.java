package art.arcane.wormholes.clientgametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicLong;

public final class TravelRenderPhaseTap {
    private static final boolean ENABLED = Boolean.getBoolean("wormholes.clientgametest.profileFrames");
    private static final Logger LOGGER = LogUtils.getLogger();

    private TravelRenderPhaseTap() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static Span begin(String phase, ClientLevel destination, Object context) {
        if (!ENABLED || !RenderSystem.isOnRenderThread()) {
            return null;
        }
        Span parent = State.CURRENT.get();
        try {
            ClientLevel level = Minecraft.getInstance().level;
            RenderPhaseEvent event = new RenderPhaseEvent();
            event.phase = phase;
            event.frame = TravelTap.frameIndex();
            event.dimension = dimension(level);
            event.level = System.identityHashCode(level);
            event.destinationDimension = destination == null && parent != null ? parent.event.destinationDimension : dimension(destination);
            event.destinationLevel = destination == null && parent != null ? parent.event.destinationLevel : System.identityHashCode(destination);
            event.context = System.identityHashCode(context);
            event.span = State.SEQUENCE.incrementAndGet();
            event.parentSpan = parent == null ? 0L : parent.event.span;
            event.depth = parent == null ? 0 : parent.event.depth + 1;
            Span span = new Span(event, parent);
            State.CURRENT.set(span);
            span.start();
            return span;
        } catch (Throwable failure) {
            State.CURRENT.set(parent);
            LOGGER.error("Cannot start travel render phase {}", phase, failure);
            return null;
        }
    }

    public static void end(Span span) {
        if (span == null) {
            return;
        }
        try {
            long wallEnded = System.nanoTime();
            long cpuEnded = cpuNanos();
            span.event.end();
            span.event.wallNanos = wallEnded - span.wallStarted;
            span.event.cpuTimeAvailable = span.cpuStarted >= 0L && cpuEnded >= 0L;
            span.event.renderThreadCpuNanos = span.event.cpuTimeAvailable ? cpuEnded - span.cpuStarted : -1L;
            span.event.commit();
        } catch (Throwable failure) {
            LOGGER.error("Cannot finish travel render phase {}", span.event.phase, failure);
        } finally {
            if (span.parent == null) {
                State.CURRENT.remove();
            } else {
                State.CURRENT.set(span.parent);
            }
        }
    }

    private static String dimension(ClientLevel level) {
        return level == null ? "" : level.dimension().identifier().toString();
    }

    private static long cpuNanos() {
        return State.CPU == null ? -1L : State.CPU.getCurrentThreadCpuTime();
    }

    private static ThreadMXBean cpuClock() {
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (!bean.isCurrentThreadCpuTimeSupported()) {
                return null;
            }
            if (!bean.isThreadCpuTimeEnabled()) {
                bean.setThreadCpuTimeEnabled(true);
            }
            return bean;
        } catch (RuntimeException failure) {
            LOGGER.error("Cannot enable render-thread CPU timing", failure);
            return null;
        }
    }

    public static final class Span {
        private final RenderPhaseEvent event;
        private final Span parent;
        private long wallStarted;
        private long cpuStarted;

        private Span(RenderPhaseEvent event, Span parent) {
            this.event = event;
            this.parent = parent;
        }

        private void start() {
            event.begin();
            cpuStarted = cpuNanos();
            wallStarted = System.nanoTime();
        }
    }

    private static final class State {
        private static final ThreadLocal<Span> CURRENT = new ThreadLocal<>();
        private static final AtomicLong SEQUENCE = new AtomicLong();
        private static final ThreadMXBean CPU = cpuClock();
    }

    @Name("wormholes.ClientRenderPhase")
    @Label("Inclusive render-thread method phase")
    @StackTrace(false)
    private static final class RenderPhaseEvent extends Event {
        String phase;
        int frame;
        String dimension;
        int level;
        String destinationDimension;
        int destinationLevel;
        int context;
        long span;
        long parentSpan;
        int depth;
        boolean inclusive = true;
        boolean cpuTimeAvailable;
        @Label("Inclusive wall duration")
        @Timespan(Timespan.NANOSECONDS)
        long wallNanos;
        @Label("Inclusive render-thread CPU duration")
        @Timespan(Timespan.NANOSECONDS)
        long renderThreadCpuNanos;
    }
}
