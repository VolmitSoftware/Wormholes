package art.arcane.wormholes.render.client.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import art.arcane.wormholes.render.ProjectedEntityEvent;
import java.util.function.Predicate;

import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.view.EntityDeltaCodec;
import art.arcane.wormholes.network.view.EntityVisual;

public final class ClientViewEntityFrames<P> implements ClientViewEntitySource<P> {
    static final long STATE_IDLE_TICKS = 200L;
    private static final long PRUNE_INTERVAL_TICKS = 100L;

    private final Scenes<P> scenes;
    private final ConcurrentHashMap<Object, Scene> captured;
    private final ConcurrentHashMap<StateKey, ObserverState> states;
    private volatile long nextPrune;

    public ClientViewEntityFrames(Scenes<P> scenes) {
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.captured = new ConcurrentHashMap<Object, Scene>();
        this.states = new ConcurrentHashMap<StateKey, ObserverState>();
    }

    @Override
    public ClientViewMessage.EntityFrame frame(P observer, UUID portal, int portalKey, long tick, boolean full, boolean hideObserver) {
        prune(tick);
        Object sceneKey = scenes.sceneKey(observer, portal);
        StateKey stateKey = new StateKey(observer, portal);
        if (sceneKey == null) {
            ObserverState previous = states.remove(stateKey);
            if (previous == null || previous.present.isEmpty() || full) {
                return null;
            }
            return new ClientViewMessage.EntityFrame(portalKey, previous.sequence + 1, List.of(), List.of(), true);
        }
        List<EntityVisual> visuals = scene(sceneKey, observer, portal, tick);
        ObserverState state = states.get(stateKey);
        if (state == null || state.portalKey != portalKey || full && !state.empty()) {
            boolean stale = state != null && state.portalKey == portalKey && !state.empty();
            ObserverState previous = state;
            state = new ObserverState(portalKey);
            if (previous != null && previous.portalKey == portalKey) {
                state.events = previous.events;
                state.pendingEvents = previous.pendingEvents;
                state.eventSequence = previous.eventSequence;
            }
            state.forcePresence = stale;
            states.put(stateKey, state);
        }
        state.touched = tick;
        return state.next(visuals, visual -> scenes.visible(observer, visual) && !(hideObserver && scenes.isObserver(observer, visual)));
    }

    @Override
    public UUID projectedId(UUID sourceId) {
        return scenes.projectedId(sourceId);
    }

    @Override
    public void event(ProjectedEntityEvent event) {
        Objects.requireNonNull(event, "event");
        UUID id = scenes.projectedId(event.entityId());
        for (ObserverState state : states.values()) {
            if (!state.present.contains(id)) {
                continue;
            }
            if (state.pendingEvents.incrementAndGet() <= ClientViewProtocol.MAX_ENTITIES_PER_FRAME) {
                state.events.add(event);
            } else {
                state.pendingEvents.decrementAndGet();
            }
        }
    }

    @Override
    public List<ClientViewMessage.EntityEvent> events(P observer, UUID portal, int portalKey) {
        ObserverState state = states.get(new StateKey(observer, portal));
        if (state == null || state.portalKey != portalKey || state.events.isEmpty()) {
            return List.of();
        }
        List<ClientViewMessage.EntityEvent> outbound = new ArrayList<>();
        ProjectedEntityEvent event;
        while ((event = state.events.poll()) != null) {
            state.pendingEvents.decrementAndGet();
            UUID id = scenes.projectedId(event.entityId());
            EntityVisual visual = state.sent.get(id);
            if (visual != null && state.present.contains(id) && scenes.visible(observer, visual)) {
                outbound.add(new ClientViewMessage.EntityEvent(portalKey, ++state.eventSequence, id, event.hurt(), event.animation(), event.yaw()));
            }
        }
        return outbound;
    }

    public int observedScenes() {
        return captured.size();
    }

    public int observerStates() {
        return states.size();
    }

    public void forget(P observer) {
        states.keySet().removeIf(key -> key.observer() == observer);
    }

    private List<EntityVisual> scene(Object sceneKey, P observer, UUID portal, long tick) {
        Scene scene = captured.get(sceneKey);
        if (scene != null && scene.tick == tick) {
            return scene.visuals;
        }
        List<EntityVisual> visuals = scenes.capture(observer, portal, tick);
        List<EntityVisual> owned = visuals == null ? List.of() : List.copyOf(visuals);
        captured.put(sceneKey, new Scene(tick, owned));
        return owned;
    }

    private void prune(long tick) {
        if (tick < nextPrune) {
            return;
        }
        nextPrune = tick + PRUNE_INTERVAL_TICKS;
        captured.values().removeIf(scene -> tick - scene.tick > PRUNE_INTERVAL_TICKS);
        states.values().removeIf(state -> tick - state.touched > STATE_IDLE_TICKS);
    }

    public interface Scenes<P> {
        Object sceneKey(P observer, UUID portal);

        List<EntityVisual> capture(P observer, UUID portal, long tick);

        default UUID projectedId(UUID sourceId) {
            return sourceId;
        }

        default boolean visible(P observer, EntityVisual visual) {
            return true;
        }

        default boolean isObserver(P observer, EntityVisual visual) {
            return false;
        }
    }

    private record Scene(long tick, List<EntityVisual> visuals) {
    }

    private record StateKey(Object observer, UUID portal) {
        @Override
        public boolean equals(Object other) {
            return other instanceof StateKey key && key.observer == observer && key.portal.equals(portal);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(observer) * 31 + portal.hashCode();
        }
    }

    static final class ObserverState {
        final int portalKey;
        final HashMap<UUID, EntityVisual> sent;
        volatile Set<UUID> present;
        boolean forcePresence;
        int sequence;
        long touched;
        int eventSequence;
        ConcurrentLinkedQueue<ProjectedEntityEvent> events = new ConcurrentLinkedQueue<>();
        AtomicInteger pendingEvents = new AtomicInteger();

        ObserverState(int portalKey) {
            this.portalKey = portalKey;
            this.sent = new HashMap<UUID, EntityVisual>();
            this.present = Set.of();
        }

        boolean empty() {
            return sent.isEmpty() && present.isEmpty();
        }

        ClientViewMessage.EntityFrame next(List<EntityVisual> visuals, Predicate<EntityVisual> visible) {
            int count = Math.min(visuals.size(), ClientViewProtocol.MAX_PRESENT_IDS_PER_FRAME);
            HashSet<UUID> current = new HashSet<UUID>(Math.max(4, count * 2));
            List<UUID> presentIds = new ArrayList<UUID>(count);
            List<EntityVisual> outbound = new ArrayList<EntityVisual>(Math.min(count, ClientViewProtocol.MAX_ENTITIES_PER_FRAME));
            for (int i = 0; i < count; i++) {
                EntityVisual visual = visuals.get(i);
                if (!visible.test(visual) || !current.add(visual.id())) {
                    continue;
                }
                EntityVisual previous = sent.get(visual.id());
                if (outbound.size() >= ClientViewProtocol.MAX_ENTITIES_PER_FRAME) {
                    if (previous != null) {
                        presentIds.add(visual.id());
                    } else {
                        current.remove(visual.id());
                    }
                    continue;
                }
                presentIds.add(visual.id());
                if (previous == null) {
                    outbound.add(sequenced(visual, EntityVisual.MODE_FULL));
                    sent.put(visual.id(), visual);
                    continue;
                }
                int mask = EntityDeltaCodec.computeMask(visual, previous);
                if (mask == 0) {
                    continue;
                }
                outbound.add((mask & EntityVisual.FIELD_MAP_DATA) != 0
                    ? sequenced(visual, EntityVisual.MODE_FULL)
                    : EntityDeltaCodec.buildDelta(visual, previous, ++sequence & 0xFFFF, mask));
                sent.put(visual.id(), visual);
            }
            Iterator<Map.Entry<UUID, EntityVisual>> iterator = sent.entrySet().iterator();
            while (iterator.hasNext()) {
                if (!current.contains(iterator.next().getKey())) {
                    iterator.remove();
                }
            }
            boolean presenceChanged = forcePresence || !current.equals(present);
            present = current;
            forcePresence = false;
            if (outbound.isEmpty() && !presenceChanged) {
                return null;
            }
            sequence++;
            return new ClientViewMessage.EntityFrame(portalKey, sequence, outbound, presenceChanged ? presentIds : List.of(), presenceChanged);
        }

        private EntityVisual sequenced(EntityVisual visual, byte mode) {
            sequence++;
            return new EntityVisual(mode, sequence & 0xFFFF, visual.presentMask(), visual.id(), visual.typeKey(), visual.x(), visual.y(),
                visual.z(), visual.height(), visual.lookX(), visual.lookY(), visual.lookZ(), visual.yaw(), visual.pitch(), visual.velocityX(),
                visual.velocityY(), visual.velocityZ(), visual.onGround(), visual.playerName(), visual.textureValue(), visual.textureSignature(),
                visual.passengerOf(), visual.leashHolder(), visual.metadata(), visual.equipment(), visual.mapData());
        }
    }
}
