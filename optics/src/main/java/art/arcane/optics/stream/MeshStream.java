package art.arcane.optics.stream;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.client.MeshPlan;

final class MeshStream<B> {
    static final int SCOPES_PER_TICK = 8;
    static final int SECTION_CHECKS_PER_TICK = 32;
    static final int MAX_PENDING_CAPTURES = 16;
    static final int MAX_IN_FLIGHT = 32;
    static final int SECTION_RESERVATION_BYTES = 64 * 1024;
    static final long TIMEOUT_NANOS = 60_000_000_000L;
    private final HashMap<Integer, State<B>> states = new HashMap<Integer, State<B>>();
    private final ArrayDeque<Ready<B>> ready = new ArrayDeque<Ready<B>>();
    private final ArrayDeque<ViewStreamMessage> control = new ArrayDeque<ViewStreamMessage>();
    private final ArrayList<Integer> schedule = new ArrayList<Integer>();
    private final HashSet<Integer> captureTurns = new HashSet<Integer>();
    private int nextScope;
    private int generation;
    private int pending;
    private int inFlight;
    private int checksThisTick;

    synchronized void beginTick() {
        checksThisTick = 0;
        captureTurns.clear();
        for (int i = 0; i < Math.min(SCOPES_PER_TICK, schedule.size()); i++) {
            if (nextScope >= schedule.size()) {
                nextScope = 0;
            }
            captureTurns.add(schedule.get(nextScope++));
        }
    }

    synchronized <P> boolean refresh(ViewStreamSlot<B> slot, ViewStreamEndpoints<P, B> portals, P player,
                                     long tick, long now, boolean destinationLight, Vec3d eye) {
        if (eye == null) {
            return false;
        }
        State<B> state = states.get(slot.key);
        ApertureDescriptor geometry = slot.geometry;
        if (state == null || !state.geometry.sameSurface(geometry)) {
            remove(slot.key);
            state = new State<B>(slot, ++generation, geometry);
            states.put(slot.key, state);
            schedule.add(slot.key);
            if (captureTurns.size() < SCOPES_PER_TICK) {
                captureTurns.add(slot.key);
            }
            control.add(new ViewStreamMessage.MeshBegin(slot.key, state.generation, MeshPlan.bounds(geometry), MeshPlan.capacity(geometry)));
        }
        if (state.eye == null || tick - state.plannedTick >= 8 && state.eye.distance(eye) > 0.5) {
            replan(state, eye, tick);
        }
        state.localAllowed = geometry.mirror() && portals.localMeshWorld(player, slot.contextId);
        if (!state.localAllowed) {
            if (!state.localSections.isEmpty()) {
                state.initialCursor = 0;
            }
            state.localSections.clear();
            if (!state.localEntities.isEmpty()) {
                state.slot.needFullEntities = true;
            }
            state.localEntities.clear();
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
        int scopes = Math.max(1, captureTurns.size());
        int quota = Math.min(Math.max(1, SECTION_CHECKS_PER_TICK / scopes), SECTION_CHECKS_PER_TICK - checksThisTick);
        if (quota <= 0) {
            return changed;
        }
        WorldChangeTracker changes = portals.meshChanges(player);
        ArrayList<Entry<B>> captures = new ArrayList<Entry<B>>(quota);
        int admissions = Math.max(1, MAX_PENDING_CAPTURES / Math.max(slot.announced ? 1 : 2, scopes));
        boolean canStart = pending < MAX_PENDING_CAPTURES && pending + inFlight < MAX_IN_FLIGHT;
        int polls = Math.min(state.capturing.size(), canStart ? Math.max(1, quota / 2) : quota);
        for (int i = 0; i < polls; i++) {
            if (state.captureCursor >= state.capturing.size()) {
                state.captureCursor = 0;
            }
            captures.add(state.capturing.get(state.captureCursor++));
        }
        while (admissions > 0 && captures.size() < quota && pending + inFlight < MAX_IN_FLIGHT && pending < MAX_PENDING_CAPTURES
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
            admissions--;
        }
        for (Entry<B> entry : captures) {
            MeshPlan.Coordinate coordinate = entry.coordinate;
            MeshPlan.Section section = new MeshPlan.Section(coordinate.x(), coordinate.y(), coordinate.z(), 0);
            checksThisTick++;
            ViewPlate<B> plate = slot.nestedChild()
                ? portals.nestedMeshSection(player, slot.portalId, slot.childId, section.clip(), geometry.depthBlocks())
                : portals.meshSection(player, slot.portalId, section.clip(), geometry.depthBlocks());
            if (plate == null || plate.dirty()) {
                boolean queued = slot.nestedChild()
                    ? portals.nestedMeshSectionQueued(player, slot.portalId, slot.childId, section.clip())
                    : portals.meshSectionQueued(player, slot.portalId, section.clip());
                if (queued) {
                    entry.started = now;
                }
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
            entry.changeRegion = plate.changeRegion();
            BrickLightSource light = destinationLight ? portals.lightBaseline(player, slot.nestedChild() ? slot.childId : slot.portalId, plate) : BrickLightSource.NONE;
            ready.add(new Ready<B>(slot, state.generation, coordinate, entry.revision, plate, light == null ? BrickLightSource.NONE : light,
                portals.meshBiomes(player, slot.nestedChild() ? slot.childId : slot.portalId, plate)));
            changed = true;
        }
        return changed;
    }

    synchronized ViewStreamMessage pollControl(IntPredicate announced) {
        Iterator<ViewStreamMessage> messages = control.iterator();
        while (messages.hasNext()) {
            ViewStreamMessage next = messages.next();
            if (!announced.test(portalKey(next))) {
                continue;
            }
            messages.remove();
            if (next instanceof ViewStreamMessage.MeshBegin begin) {
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

    synchronized boolean acknowledge(ViewStreamMessage.MeshAck ack) {
        State<B> state = states.get(ack.portalKey());
        if (state == null || state.generation != ack.generation()) {
            return false;
        }
        Entry<B> entry = state.entries.get(new MeshPlan.Coordinate(ack.sectionX(), ack.sectionY(), ack.sectionZ()));
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

    synchronized boolean local(ViewStreamMessage.MeshLocal message) {
        State<B> state = states.get(message.portalKey());
        if (state == null || state.generation != message.generation() || !state.localAllowed
            || message.sequence() <= state.localSequence) {
            return false;
        }
        BlockBox bounds = MeshPlan.bounds(state.geometry);
        for (ViewStreamMessage.MeshCoordinate section : message.sections()) {
            MeshPlan.Section target = new MeshPlan.Section(section.x(), section.y(), section.z(), 0);
            if (!inside(bounds, target)) {
                return false;
            }
        }
        if (message.available()) {
            Set<UUID> entities = new HashSet<>(state.localEntities);
            entities.addAll(message.entities());
            Set<MeshPlan.Coordinate> sections = new HashSet<>(state.localSections);
            for (ViewStreamMessage.MeshCoordinate section : message.sections()) {
                sections.add(new MeshPlan.Coordinate(section.x(), section.y(), section.z()));
            }
            if (entities.size() > 4096 || sections.size() > MeshPlan.capacity(state.geometry)) {
                return false;
            }
        }
        state.localSequence = message.sequence();
        for (ViewStreamMessage.MeshCoordinate section : message.sections()) {
            MeshPlan.Coordinate coordinate = new MeshPlan.Coordinate(section.x(), section.y(), section.z());
            if (message.available()) {
                state.localSections.add(coordinate);
            } else {
                state.localSections.remove(coordinate);
            }
            Entry<B> entry = state.entries.remove(coordinate);
            if (entry != null) {
                state.capturing.remove(entry);
                state.awaiting.remove(entry);
                state.residents.remove(entry);
                release(entry);
            }
        }
        if (message.available()) {
            if (state.localEntities.addAll(message.entities())) {
                state.slot.needFullEntities = true;
            }
        } else {
            if (state.localEntities.removeAll(message.entities())) {
                state.slot.needFullEntities = true;
            }
        }
        ready.removeIf(item -> item.slot.key == message.portalKey() && state.localSections.contains(item.coordinate));
        state.initialCursor = 0;
        state.captureCursor = 0;
        return true;
    }

    private static boolean inside(BlockBox bounds, MeshPlan.Section section) {
        long x = (long) section.x() * 16;
        long y = (long) section.y() * 16;
        long z = (long) section.z() * 16;
        return x + 16 > bounds.minX() && x < (long) bounds.minX() + bounds.sizeX()
            && y + 16 > bounds.minY() && y < (long) bounds.minY() + bounds.sizeY()
            && z + 16 > bounds.minZ() && z < (long) bounds.minZ() + bounds.sizeZ();
    }

    synchronized ViewStreamMessage.EntityFrame localEntities(ViewStreamMessage.EntityFrame frame) {
        State<B> state = states.get(frame.portalKey());
        if (state == null || state.localEntities.isEmpty()) {
            return frame;
        }
        List<EntitySnapshot> entities = new ArrayList<>(frame.entities().size());
        List<UUID> present = new ArrayList<>(frame.presentIds().size());
        for (EntitySnapshot entity : frame.entities()) {
            if (!state.localEntities.contains(entity.id())) {
                entities.add(entity);
            }
        }
        for (UUID id : frame.presentIds()) {
            if (!state.localEntities.contains(id)) {
                present.add(id);
            }
        }
        return entities.size() == frame.entities().size() && present.size() == frame.presentIds().size() ? frame
            : new ViewStreamMessage.EntityFrame(frame.portalKey(), frame.entitySeq(), entities, present, frame.presence());
    }

    synchronized boolean localEntity(int portalKey, UUID entity) {
        State<B> state = states.get(portalKey);
        return state != null && state.localEntities.contains(entity);
    }

    synchronized boolean cached(ViewStreamMessage.MeshCached message) {
        State<B> state = states.get(message.portalKey());
        if (state == null || state.generation != message.generation() || message.sequence() <= state.cacheSequence) {
            return false;
        }
        BlockBox bounds = MeshPlan.bounds(state.geometry);
        HashSet<MeshPlan.Coordinate> added = new HashSet<>(state.cached.keySet());
        for (ViewStreamMessage.MeshClaim claim : message.claims()) {
            MeshPlan.Section section = new MeshPlan.Section(claim.x(), claim.y(), claim.z(), 0);
            if (!inside(bounds, section)) {
                return false;
            }
            added.add(section.coordinate());
        }
        if (message.available() && added.size() > MeshPlan.capacity(state.geometry)) {
            return false;
        }
        state.cacheSequence = message.sequence();
        for (ViewStreamMessage.MeshClaim claim : message.claims()) {
            MeshPlan.Coordinate coordinate = new MeshPlan.Coordinate(claim.x(), claim.y(), claim.z());
            if (message.available()) {
                state.cached.put(coordinate, claim.hash());
            } else {
                state.cached.remove(coordinate);
            }
        }
        return true;
    }

    synchronized boolean reuse(Ready<B> next, long hash) {
        State<B> state = states.get(next.slot.key);
        Long claimed = state == null ? null : state.cached.get(next.coordinate);
        return state != null && state.generation == next.generation && claimed != null && claimed.longValue() == hash && current(next);
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

    private void replan(State<B> state, Vec3d eye, long tick) {
        state.eye = eye;
        state.plannedTick = tick;
        state.order = MeshPlan.visible(state.geometry, eye);
        state.wanted.clear();
        for (MeshPlan.Section section : state.order) {
            state.wanted.add(section.coordinate());
        }
        state.initialCursor = 0;
    }

    private Entry<B> initialEntry(State<B> state) {
        while (state.initialCursor < state.order.size()) {
            MeshPlan.Coordinate coordinate = state.order.get(state.initialCursor++).coordinate();
            if (!state.localSections.contains(coordinate) && !state.entries.containsKey(coordinate)) {
                Entry<B> entry = new Entry<B>(coordinate);
                state.entries.put(coordinate, entry);
                state.residents.add(entry);
                return entry;
            }
        }
        return null;
    }

    private Entry<B> refreshEntry(State<B> state, long tick, WorldChangeTracker changes) {
        if (changes != null) {
            if (++state.refreshSequence % 2 == 0) {
                int count = Math.min(16, state.order.size());
                for (int visited = 0; visited < count; visited++) {
                    if (state.priorityCursor >= count) {
                        state.priorityCursor = 0;
                    }
                    Entry<B> entry = state.entries.get(state.order.get(state.priorityCursor++).coordinate());
                    if (dirty(entry, changes)) {
                        return entry;
                    }
                }
            }
            for (int visited = 0; visited < Math.min(64, state.residents.size()); visited++) {
                if (state.dirtyLimit == 0 || state.dirtyCursor >= state.dirtyLimit || state.dirtyCursor >= state.residents.size()) {
                    state.dirtyCursor = 0;
                    state.dirtyLimit = state.residents.size();
                }
                Entry<B> entry = state.residents.get(state.dirtyCursor++);
                if (state.localSections.contains(entry.coordinate) || !state.wanted.contains(entry.coordinate) || entry.awaiting || entry.queued || entry.pending) {
                    continue;
                }
                if (dirty(entry, changes)) {
                    return entry;
                }
            }
        }
        for (int visited = 0; visited < Math.min(64, state.residents.size()); visited++) {
            if (state.cursor >= state.residents.size()) {
                state.cursor = 0;
            }
            Entry<B> entry = state.residents.get(state.cursor++);
            if (!state.localSections.contains(entry.coordinate) && state.wanted.contains(entry.coordinate) && !entry.awaiting && !entry.queued && !entry.pending && tick - entry.checkedTick >= 20) {
                return entry;
            }
        }
        return null;
    }

    private boolean dirty(Entry<B> entry, WorldChangeTracker changes) {
        if (entry == null || entry.awaiting || entry.queued || entry.pending) {
            return false;
        }
        ViewPlate<B> previous = entry.previous.get();
        return previous != null && (!previous.refreshDirt(changes) || previous.dirty())
            || previous == null && entry.changeRegion != null && entry.changeRegion.dirty(changes);
    }

    private void release(Entry<B> entry) {
        if (entry.pending) {
            pending--;
        }
        if (entry.awaiting) {
            inFlight--;
        }
    }

    private static int portalKey(ViewStreamMessage message) {
        return switch (message) {
            case ViewStreamMessage.MeshBegin begin -> begin.portalKey();
            case ViewStreamMessage.MeshDrop drop -> drop.portalKey();
            default -> -1;
        };
    }

    record Ready<B>(ViewStreamSlot<B> slot, int generation, MeshPlan.Coordinate coordinate, int revision, ViewPlate<B> plate, BrickLightSource light, SectionBiomes biomes) {
    }

    private static final class State<B> {
        private final ViewStreamSlot<B> slot;
        private final int generation;
        private final ApertureDescriptor geometry;
        private final ArrayList<Entry<B>> capturing = new ArrayList<Entry<B>>(MAX_PENDING_CAPTURES);
        private final HashMap<MeshPlan.Coordinate, Entry<B>> entries = new HashMap<MeshPlan.Coordinate, Entry<B>>();
        private final ArrayList<Entry<B>> residents = new ArrayList<Entry<B>>();
        private final HashSet<MeshPlan.Coordinate> wanted = new HashSet<MeshPlan.Coordinate>();
        private final HashSet<Entry<B>> awaiting = new HashSet<Entry<B>>();
        private final Set<MeshPlan.Coordinate> localSections = new HashSet<>();
        private final Set<UUID> localEntities = new HashSet<>();
        private final HashMap<MeshPlan.Coordinate, Long> cached = new HashMap<>();
        private int localSequence;
        private int cacheSequence;
        private boolean localAllowed;
        private List<MeshPlan.Section> order = new ArrayList<MeshPlan.Section>();
        private Vec3d eye;
        private long plannedTick;
        private int cursor;
        private int dirtyCursor;
        private int dirtyLimit;
        private int priorityCursor;
        private int refreshSequence;
        private int initialCursor;
        private int captureCursor;
        private int captureSequence;
        private int revision;
        private boolean begun;

        private State(ViewStreamSlot<B> slot, int generation, ApertureDescriptor geometry) {
            this.slot = slot;
            this.generation = generation;
            this.geometry = geometry;
        }
    }

    private static final class Entry<B> {
        private final MeshPlan.Coordinate coordinate;
        private WeakReference<ViewPlate<B>> previous = new WeakReference<ViewPlate<B>>(null);
        private ViewPlate.ChangeRegion changeRegion;
        private int revision;
        private long checkedTick;
        private long started;
        private boolean pending;
        private boolean queued;
        private boolean awaiting;
        private boolean hasHash;
        private long contentHash;
        private int backingState;

        private Entry(MeshPlan.Coordinate coordinate) {
            this.coordinate = coordinate;
        }
    }
}
