package art.arcane.wormholes.render.client.session;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import art.arcane.optics.stream.EntityFrames;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamScene;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import art.arcane.wormholes.network.client.FxExtension;
import art.arcane.wormholes.network.client.FxMessage;

public final class ClientViewSceneFx<P> implements ViewStreamScene<P> {
    static final long ATMOSPHERE_RESYNC_TICKS = 600L;
    static final long DAY_TIME_DRIFT_TICKS = 40L;
    static final float WEATHER_EPSILON = 0.02F;
    private static final long PRUNE_INTERVAL_TICKS = 100L;

    private final Effects<P> effects;
    private final ConcurrentHashMap<StateKey, PortalState> states;
    private volatile long nextPrune;

    public ClientViewSceneFx(Effects<P> effects) {
        this.effects = Objects.requireNonNull(effects, "effects");
        this.states = new ConcurrentHashMap<StateKey, PortalState>();
    }

    @Override
    public long effectCapability() {
        return ClientViewExtensions.FX_EMITTERS;
    }

    @Override
    public ViewStreamMessage.Extension effects(P observer, UUID portal, int portalKey, long tick, boolean full) {
        prune(tick);
        PortalState state = state(observer, portal, portalKey, tick);
        if (full) {
            state.emitters = null;
        }
        List<FxMessage.FxEmitter> emitters = effects.emitters(observer, portal, tick);
        List<FxMessage.FxEmitter> current = emitters == null ? List.of() : emitters;
        if (current.size() > FxMessage.MAX_FX_EMITTERS) {
            current = current.subList(0, FxMessage.MAX_FX_EMITTERS);
        }
        List<FxMessage.FxEmitter> previous = state.emitters;
        if (previous == null ? current.isEmpty() : previous.equals(current)) {
            return null;
        }
        state.emitters = List.copyOf(current);
        return FxExtension.INSTANCE.wrap(new FxMessage.Fx(portalKey, state.emitters));
    }

    @Override
    public ViewStreamMessage.Atmosphere atmosphere(P observer, UUID portal, int portalKey, long tick, boolean full) {
        PortalState state = state(observer, portal, portalKey, tick);
        if (full) {
            state.atmosphere = null;
        }
        Sample sample = effects.atmosphere(observer, portal, tick);
        if (sample == null) {
            if (state.atmosphere == null) {
                return null;
            }
            state.atmosphere = null;
            return new ViewStreamMessage.Atmosphere(portalKey, 0L, 0.0F, 0.0F, ViewStreamMessage.Atmosphere.FLAG_RESTORE);
        }
        if (!due(state, sample, tick)) {
            return null;
        }
        state.atmosphere = sample;
        state.atmosphereTick = tick;
        return new ViewStreamMessage.Atmosphere(portalKey, sample.dayTime(), sample.rain(), sample.thunder(), sample.flags());
    }

    @Override
    public ViewStreamMessage.Environment environment(P observer, UUID portal, int portalKey, long tick, boolean full) {
        prune(tick);
        PortalState state = state(observer, portal, portalKey, tick);
        if (!full && tick < state.nextEnvironmentTick) {
            return null;
        }
        if (full) {
            state.environment = null;
        }
        return environment(state, effects.environment(observer, portal, tick), tick);
    }

    @Override
    public ViewStreamMessage.Environment nestedEnvironment(P observer, UUID parent, UUID portal, int portalKey, long tick, boolean full) {
        prune(tick);
        PortalState state = state(observer, portal, portalKey, tick);
        if (!full && tick < state.nextEnvironmentTick) {
            return null;
        }
        if (full) {
            state.environment = null;
        }
        return environment(state, effects.nestedEnvironment(observer, parent, portal, tick), tick);
    }

    private static ViewStreamMessage.Environment environment(PortalState state, EnvironmentState sample, long tick) {
        state.nextEnvironmentTick = tick + 5L;
        if (sample == null || sample.equals(state.environment)) {
            return null;
        }
        state.environment = sample;
        return new ViewStreamMessage.Environment(state.portalKey, sample);
    }

    @Override
    public boolean environmentUnavailable(P observer, UUID parent, UUID portal) {
        return effects.environmentUnavailable(observer, parent, portal);
    }

    public int states() {
        return states.size();
    }

    private static boolean due(PortalState state, Sample sample, long tick) {
        Sample previous = state.atmosphere;
        if (previous == null || previous.flags() != sample.flags()) {
            return true;
        }
        if (Math.abs(previous.rain() - sample.rain()) >= WEATHER_EPSILON || Math.abs(previous.thunder() - sample.thunder()) >= WEATHER_EPSILON) {
            return true;
        }
        long elapsed = tick - state.atmosphereTick;
        if (elapsed >= ATMOSPHERE_RESYNC_TICKS) {
            return true;
        }
        long expected = previous.dayTime() + (previous.clockRunning() ? elapsed : 0L);
        return Math.abs(sample.dayTime() - expected) > DAY_TIME_DRIFT_TICKS;
    }

    private PortalState state(P observer, UUID portal, int portalKey, long tick) {
        StateKey key = new StateKey(observer, portal, portalKey);
        PortalState state = states.get(key);
        if (state == null || state.portalKey != portalKey) {
            state = new PortalState(portalKey);
            states.put(key, state);
        }
        state.touched = tick;
        return state;
    }

    private void prune(long tick) {
        if (tick < nextPrune) {
            return;
        }
        nextPrune = tick + PRUNE_INTERVAL_TICKS;
        states.values().removeIf(state -> tick - state.touched > EntityFrames.STATE_IDLE_TICKS);
    }

    public interface Effects<P> {
        List<FxMessage.FxEmitter> emitters(P observer, UUID portal, long tick);

        Sample atmosphere(P observer, UUID portal, long tick);

        default boolean environmentUnavailable(P observer, UUID parent, UUID portal) {
            return false;
        }

        default EnvironmentState environment(P observer, UUID portal, long tick) {
            return null;
        }

        default EnvironmentState nestedEnvironment(P observer, UUID parent, UUID portal, long tick) {
            return null;
        }
    }

    public record Sample(long dayTime, boolean clockRunning, float rain, float thunder, int flags) {
    }

    private record StateKey(Object observer, UUID portal, int portalKey) {
        @Override
        public boolean equals(Object other) {
            return other instanceof StateKey key && key.observer == observer && key.portal.equals(portal) && key.portalKey == portalKey;
        }

        @Override
        public int hashCode() {
            return (System.identityHashCode(observer) * 31 + portal.hashCode()) * 31 + portalKey;
        }
    }

    private static final class PortalState {
        private final int portalKey;
        private List<FxMessage.FxEmitter> emitters;
        private Sample atmosphere;
        private EnvironmentState environment;
        private long nextEnvironmentTick;
        private long atmosphereTick;
        private long touched;

        private PortalState(int portalKey) {
            this.portalKey = portalKey;
        }
    }
}
