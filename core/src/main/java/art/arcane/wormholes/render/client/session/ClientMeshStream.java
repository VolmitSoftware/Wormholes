package art.arcane.wormholes.render.client.session;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Iterator;
import java.util.function.IntPredicate;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.SectionBiomes;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.plate.ViewPlate;

final class ClientMeshStream<B> {
    static final int CAPTURES_PER_TICK = 8;
    static final int MAX_PENDING_CAPTURES = 16;
    static final int MAX_IN_FLIGHT = 32;
    static final int SECTION_RESERVATION_BYTES = 64 * 1024;
    static final long TIMEOUT_NANOS = 60_000_000_000L;
    private final HashMap<Integer, State<B>> states = new HashMap<Integer, State<B>>();
    private final ArrayDeque<Ready<B>> ready = new ArrayDeque<Ready<B>>();
    private final ArrayDeque<ClientViewMessage> control = new ArrayDeque<ClientViewMessage>();
    private final ArrayList<Integer> schedule = new ArrayList<Integer>();
    private final HashSet<Integer> captureTurns = new HashSet<Integer>();
    private int nextScope;
    private int generation;
    private int pending;
    private int inFlight;
    private int capturesThisTick;

    synchronized void beginTick() {
        capturesThisTick = 0;
        captureTurns.clear();
        for (int i = 0; i < Math.min(CAPTURES_PER_TICK, schedule.size()); i++) {
            if (nextScope >= schedule.size()) {
                nextScope = 0;
            }
            captureTurns.add(schedule.get(nextScope++));
        }
    }

    synchronized <P> boolean refresh(ClientViewPortalSlot<B> slot, ClientViewPortalAccess<P, B> portals, P player,
                                     long tick, long now, boolean destinationLight, GeometryVector eye) {
        if (eye == null) {
            return false;
        }
        State<B> state = states.get(slot.key);
        ClientPortalGeometry geometry = slot.geometry;
        if (state == null || !state.geometry.equals(geometry)) {
            remove(slot.key);
            state = new State<B>(slot, ++generation, geometry);
            states.put(slot.key, state);
            schedule.add(slot.key);
            if (captureTurns.size() < CAPTURES_PER_TICK) {
                captureTurns.add(slot.key);
            }
            control.add(new ClientViewMessage.MeshBegin(slot.key, state.generation, ClientMeshPlan.bounds(geometry), ClientMeshPlan.capacity(geometry)));
        }
        if (state.eye == null || tick - state.plannedTick >= 8 && state.eye.distance(eye) > 0.5) {
            replan(state, eye, tick);
        }
        for (Entry<B> entry : state.awaiting) {
            if (now - entry.started >= TIMEOUT_NANOS) {
                throw new IllegalStateException("mesh ACK timeout, generation " + state.generation + ", section " + entry.coordinate);
            }
        }
        boolean changed = !control.isEmpty();
        if (!captureTurns.contains(slot.key) || pending == 0 && inFlight >= MAX_IN_FLIGHT) {
            return changed;
        }
        int quota = Math.min(Math.max(1, CAPTURES_PER_TICK / Math.max(1, captureTurns.size())), CAPTURES_PER_TICK - capturesThisTick);
        if (quota <= 0) {
            return changed;
        }
        ProjectionWorldChangeTracker changes = portals.meshChanges(player);
        ArrayList<Entry<B>> captures = new ArrayList<Entry<B>>(quota);
        boolean canStart = pending < MAX_PENDING_CAPTURES && pending + inFlight < MAX_IN_FLIGHT;
        int polls = Math.min(state.capturing.size(), canStart ? Math.max(1, quota / 2) : quota);
        for (int i = 0; i < polls; i++) {
            if (state.captureCursor >= state.capturing.size()) {
                state.captureCursor = 0;
            }
            captures.add(state.capturing.get(state.captureCursor++));
        }
        while (captures.size() < quota && pending + inFlight < MAX_IN_FLIGHT && pending < MAX_PENDING_CAPTURES
            && !state.order.isEmpty()) {
            boolean refresh = ++state.captureSequence % 4 == 0;
            Entry<B> entry = refresh ? refreshEntry(state, tick, changes) : initialEntry(state);
            if (entry == null) {
                entry = refresh ? initialEntry(state) : refreshEntry(state, tick, changes);
            }
            if (entry == null) {
                break;
            }
            entry.pending = true;
            entry.started = now;
            state.capturing.add(entry);
            pending++;
            captures.add(entry);
        }
        for (Entry<B> entry : captures) {
            ClientMeshPlan.Coordinate coordinate = entry.coordinate;
            ClientMeshPlan.Section section = new ClientMeshPlan.Section(coordinate.x(), coordinate.y(), coordinate.z(), 0);
            capturesThisTick++;
            ViewPlate<B> plate = slot.nestedChild()
                ? portals.nestedMeshSection(player, slot.portalId, slot.childId, section.clip(), geometry.depthBlocks())
                : portals.meshSection(player, slot.portalId, section.clip(), geometry.depthBlocks());
            if (plate == null || entry.previous.get() == plate && plate.dirty()) {
                if (now - entry.started >= TIMEOUT_NANOS) {
                    throw new IllegalStateException("mesh capture timeout, generation " + state.generation + ", section " + coordinate);
                }
                continue;
            }
            state.capturing.remove(entry);
            entry.checkedTick = tick;
            if (entry.previous.get() == plate) {
                entry.pending = false;
                pending--;
                continue;
            }
            entry.queued = true;
            entry.revision = ++state.revision;
            entry.previous = new WeakReference<ViewPlate<B>>(plate);
            BrickLightSource light = destinationLight ? portals.lightBaseline(player, slot.nestedChild() ? slot.childId : slot.portalId, plate) : BrickLightSource.NONE;
            ready.add(new Ready<B>(slot, state.generation, coordinate, entry.revision, plate, light == null ? BrickLightSource.NONE : light,
                portals.meshBiomes(player, slot.nestedChild() ? slot.childId : slot.portalId, plate)));
            changed = true;
        }
        return changed;
    }

    synchronized ClientViewMessage pollControl(IntPredicate announced) {
        Iterator<ClientViewMessage> messages = control.iterator();
        while (messages.hasNext()) {
            ClientViewMessage next = messages.next();
            if (!announced.test(portalKey(next))) {
                continue;
            }
            messages.remove();
            if (next instanceof ClientViewMessage.MeshBegin begin) {
                State<B> state = states.get(begin.portalKey());
                if (state != null && state.generation == begin.generation()) {
                    state.begun = true;
                }
            }
            return next;
        }
        return null;
    }

    synchronized Ready<B> poll(long now) {
        if (inFlight >= MAX_IN_FLIGHT) {
            return null;
        }
        for (int waiting = ready.size(); waiting > 0; waiting--) {
            Ready<B> next = ready.remove();
            State<B> state = states.get(next.slot.key);
            Entry<B> entry = state == null ? null : state.entries.get(next.coordinate);
            if (state == null || state.generation != next.generation || entry == null || entry.revision != next.revision) {
                continue;
            }
            if (!state.begun || !next.slot.announced || next.slot.sentGeometry != next.slot.geometry) {
                ready.add(next);
                continue;
            }
            entry.pending = false;
            entry.queued = false;
            entry.awaiting = true;
            state.awaiting.add(entry);
            entry.started = now;
            pending--;
            inFlight++;
            return next;
        }
        return null;
    }

    synchronized boolean staleRefusal(int portalKey, int generation) {
        State<B> state = states.get(portalKey);
        return state != null && state.generation != generation;
    }

    synchronized boolean acknowledge(ClientViewMessage.MeshAck ack) {
        State<B> state = states.get(ack.portalKey());
        if (state == null || state.generation != ack.generation()) {
            return false;
        }
        Entry<B> entry = state.entries.get(new ClientMeshPlan.Coordinate(ack.sectionX(), ack.sectionY(), ack.sectionZ()));
        if (entry == null || !entry.awaiting || entry.revision != ack.revision()) {
            return false;
        }
        entry.awaiting = false;
        state.awaiting.remove(entry);
        inFlight--;
        return true;
    }

    synchronized boolean unchanged(Ready<B> next, long contentHash, int backingState) {
        State<B> state = states.get(next.slot.key);
        Entry<B> entry = state == null ? null : state.entries.get(next.coordinate);
        if (entry == null || state.generation != next.generation || entry.revision != next.revision || !entry.awaiting) {
            return true;
        }
        if (entry.hasHash && entry.contentHash == contentHash && entry.backingState == backingState) {
            entry.awaiting = false;
            state.awaiting.remove(entry);
            inFlight--;
            return true;
        }
        entry.hasHash = true;
        entry.contentHash = contentHash;
        entry.backingState = backingState;
        return false;
    }

    synchronized boolean current(Ready<B> next) {
        State<B> state = states.get(next.slot.key);
        Entry<B> entry = state == null ? null : state.entries.get(next.coordinate);
        return state != null && state.generation == next.generation && entry != null && entry.revision == next.revision
            && entry.awaiting && !next.slot.failed && next.slot.laneAttached;
    }

    synchronized void remove(int portalKey) {
        State<B> state = states.remove(portalKey);
        schedule.remove(Integer.valueOf(portalKey));
        captureTurns.remove(portalKey);
        if (state != null) {
            for (Entry<B> entry : state.entries.values()) {
                release(entry);
            }
        }
        ready.removeIf(item -> item.slot.key == portalKey);
        control.removeIf(message -> portalKey(message) == portalKey);
    }

    synchronized void clear() {
        states.clear();
        schedule.clear();
        captureTurns.clear();
        nextScope = 0;
        ready.clear();
        control.clear();
        pending = 0;
        inFlight = 0;
    }

    private void replan(State<B> state, GeometryVector eye, long tick) {
        state.eye = eye;
        state.plannedTick = tick;
        state.order = ClientMeshPlan.visible(state.geometry, eye);
        state.wanted.clear();
        for (ClientMeshPlan.Section section : state.order) {
            state.wanted.add(section.coordinate());
        }
        state.initialCursor = 0;
    }

    private Entry<B> initialEntry(State<B> state) {
        while (state.initialCursor < state.order.size()) {
            ClientMeshPlan.Coordinate coordinate = state.order.get(state.initialCursor++).coordinate();
            if (!state.entries.containsKey(coordinate)) {
                Entry<B> entry = new Entry<B>(coordinate);
                state.entries.put(coordinate, entry);
                state.residents.add(entry);
                return entry;
            }
        }
        return null;
    }

    private Entry<B> refreshEntry(State<B> state, long tick, ProjectionWorldChangeTracker changes) {
        if (changes != null) {
            for (int visited = 0; visited < Math.min(64, state.residents.size()); visited++) {
                if (state.dirtyCursor >= state.residents.size()) {
                    state.dirtyCursor = 0;
                }
                Entry<B> entry = state.residents.get(state.dirtyCursor++);
                if (!state.wanted.contains(entry.coordinate) || entry.awaiting || entry.queued || entry.pending) {
                    continue;
                }
                ViewPlate<B> previous = entry.previous.get();
                if (previous != null && (!previous.refreshDirt(changes) || previous.dirty())) {
                    return entry;
                }
            }
        }
        for (int visited = 0; visited < Math.min(64, state.residents.size()); visited++) {
            if (state.cursor >= state.residents.size()) {
                state.cursor = 0;
            }
            Entry<B> entry = state.residents.get(state.cursor++);
            if (state.wanted.contains(entry.coordinate) && !entry.awaiting && !entry.queued && !entry.pending && tick - entry.checkedTick >= 20) {
                return entry;
            }
        }
        return null;
    }

    private void release(Entry<B> entry) {
        if (entry.pending) {
            pending--;
        }
        if (entry.awaiting) {
            inFlight--;
        }
    }

    private static int portalKey(ClientViewMessage message) {
        return switch (message) {
            case ClientViewMessage.MeshBegin begin -> begin.portalKey();
            case ClientViewMessage.MeshDrop drop -> drop.portalKey();
            default -> -1;
        };
    }

    record Ready<B>(ClientViewPortalSlot<B> slot, int generation, ClientMeshPlan.Coordinate coordinate, int revision, ViewPlate<B> plate, BrickLightSource light, SectionBiomes biomes) {
    }

    private static final class State<B> {
        private final ClientViewPortalSlot<B> slot;
        private final int generation;
        private final ClientPortalGeometry geometry;
        private final ArrayList<Entry<B>> capturing = new ArrayList<Entry<B>>(MAX_PENDING_CAPTURES);
        private final HashMap<ClientMeshPlan.Coordinate, Entry<B>> entries = new HashMap<ClientMeshPlan.Coordinate, Entry<B>>();
        private final ArrayList<Entry<B>> residents = new ArrayList<Entry<B>>();
        private final HashSet<ClientMeshPlan.Coordinate> wanted = new HashSet<ClientMeshPlan.Coordinate>();
        private final HashSet<Entry<B>> awaiting = new HashSet<Entry<B>>();
        private List<ClientMeshPlan.Section> order = new ArrayList<ClientMeshPlan.Section>();
        private GeometryVector eye;
        private long plannedTick;
        private int cursor;
        private int dirtyCursor;
        private int initialCursor;
        private int captureCursor;
        private int captureSequence;
        private int revision;
        private boolean begun;

        private State(ClientViewPortalSlot<B> slot, int generation, ClientPortalGeometry geometry) {
            this.slot = slot;
            this.generation = generation;
            this.geometry = geometry;
        }
    }

    private static final class Entry<B> {
        private final ClientMeshPlan.Coordinate coordinate;
        private WeakReference<ViewPlate<B>> previous = new WeakReference<ViewPlate<B>>(null);
        private int revision;
        private long checkedTick;
        private long started;
        private boolean pending;
        private boolean queued;
        private boolean awaiting;
        private boolean hasHash;
        private long contentHash;
        private int backingState;

        private Entry(ClientMeshPlan.Coordinate coordinate) {
            this.coordinate = coordinate;
        }
    }
}
