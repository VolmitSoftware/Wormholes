package art.arcane.optics.stream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.client.MeshPlan;

public final class ViewStreamSession<O, B> {
    static final long BRICK_MISS_TIMEOUT_NANOS = 5_000_000_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int PLAY_PHASE_GRACE_MILLIS = ViewStreamLimits.PLAY_PHASE_PENDING_TICKS * 50;
    private static final int NESTED_GEOMETRY = 1;
    private static final int NESTED_PLATE = 1 << 1;
    private static final int MAX_PENDING_BURSTS = 1024;
    static final int NATIVE_RETRY_TICKS = 20;

    private final ViewStreamSessionRegistry<O, B> registry;
    private final ViewStreamPlatform<O, B> platform;
    private final UUID playerId;
    private final O player;
    private final long zeroCopyNonce;
    private final ViewStreamLane lane;
    private final MeshStream<B> mesh = new MeshStream<B>();
    private final Hooks<O> hooks;
    private final Predicate<ViewStreamMessage> sender;
    private final ConcurrentLinkedQueue<Command<B>> inbox;
    private final Object handshakeLock;
    private final ViewStreamRateLimiter limiter;
    private final AtomicInteger sequence;
    private final IntSupplier laneSequence;
    private final HashMap<UUID, ViewStreamSlot<B>> slots;
    private final ArrayList<ViewStreamSlot<B>> slotList;
    private final ArrayList<UUID> interest;
    private final ArrayList<UUID> effectInterest;
    private final HashSet<UUID> rejected;
    private final HashMap<UUID, Long> retryAfter = new HashMap<>();
    private final AtomicReference<ViewStreamMessage.ResetReason> recovery = new AtomicReference<>();
    private long serverTick;
    private final ArrayList<UUID> nestedScratch;
    private final ArrayList<ViewStreamSlot<B>> laneSlots;
    private final ArrayList<Scene<B>> laneScene;
    private final ArrayList<ViewStreamMessage.Extension> laneBursts;
    private final AtomicInteger pendingBursts;
    private final AtomicLong framesSent;
    private final AtomicLong bytesSent;
    private final AtomicLong groupsSent;
    private final AtomicLong c2sDropped;
    private final AtomicLong c2sStale;
    private final AtomicLong staleMisses;
    private final AtomicLong lateSwitches;
    private volatile ViewStreamSessionState state;
    private volatile ViewStreamAckWindow window;
    private volatile long caps;
    private volatile int sessionId;
    private volatile ViewStreamMessage.ViewStats viewStats;
    private volatile long viewStatsMillis;
    private volatile int attended;
    private volatile boolean paused;
    private volatile boolean closed;
    private ViewStreamHandshake handshake;
    private String brandTag;
    private int nextPortalKey;
    private SessionPalette.Cursor cursor;
    private FrameSplitter splitter;
    private long laneCaps;
    private boolean laneOpen;
    private boolean entitySelfPending;
    private boolean laneSent;
    private int lastLaneSequence;

    ViewStreamSession(ViewStreamSessionRegistry<O, B> registry, UUID playerId, O player, long zeroCopyNonce) {
        this.registry = registry;
        this.platform = registry.platform();
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.player = Objects.requireNonNull(player, "player");
        this.zeroCopyNonce = zeroCopyNonce;
        this.lane = new ViewStreamLane(platform.lanes(), this::drainSafely);
        this.inbox = new ConcurrentLinkedQueue<Command<B>>();
        this.handshakeLock = new Object();
        this.limiter = new ViewStreamRateLimiter(registry.codec()::name);
        this.sequence = new AtomicInteger();
        this.laneSequence = this::nextLaneSequence;
        this.slots = new HashMap<UUID, ViewStreamSlot<B>>();
        this.slotList = new ArrayList<ViewStreamSlot<B>>();
        this.interest = new ArrayList<UUID>();
        this.effectInterest = new ArrayList<UUID>();
        this.rejected = new HashSet<UUID>();
        this.nestedScratch = new ArrayList<UUID>(4);
        this.laneSlots = new ArrayList<ViewStreamSlot<B>>();
        this.laneScene = new ArrayList<Scene<B>>();
        this.laneBursts = new ArrayList<ViewStreamMessage.Extension>();
        this.pendingBursts = new AtomicInteger();
        this.framesSent = new AtomicLong();
        this.bytesSent = new AtomicLong();
        this.groupsSent = new AtomicLong();
        this.c2sDropped = new AtomicLong();
        this.c2sStale = new AtomicLong();
        this.staleMisses = new AtomicLong();
        this.lateSwitches = new AtomicLong();
        this.state = ViewStreamSessionState.VANILLA;
        this.nextPortalKey = 1;
        this.sender = this::send;
        this.hooks = Objects.requireNonNull(platform.hooks().create(this), "hooks");
    }

    public UUID playerId() {
        return playerId;
    }

    public O player() {
        return player;
    }

    public ViewStreamSessionState state() {
        return state;
    }

    public boolean holdsVanilla() {
        return state == ViewStreamSessionState.PENDING;
    }

    public long caps() {
        return caps;
    }

    public boolean nativeRendererSelected() {
        return !closed && state == ViewStreamSessionState.CLIENT_VIEW && ViewStreamCapability.MESH_RENDER.in(caps);
    }

    public Hooks<O> hooks() {
        return hooks;
    }

    public ApertureDescriptor endpointGeometry(UUID endpoint) {
        ApertureDescriptor geometry = platform.endpoints().geometry(player, endpoint, registry.palette());
        return geometry == null ? null : geometry.withParent(0).withNested(List.of());
    }

    public boolean send(ViewStreamMessage message) {
        if (closed) {
            return false;
        }
        try {
            byte[] frame = registry.codec().encodeS2C(message, sequence.getAndIncrement(), 0);
            if (frame.length > registry.options().maxFrameBytes()) {
                return false;
            }
            platform.transport().send(player, frame);
            platform.transport().flush(player);
            framesSent.incrementAndGet();
            bytesSent.addAndGet(frame.length);
            return true;
        } catch (ViewStreamProtocolException failure) {
            platform.warnings().accept("View stream " + registry.codec().name(message) + " failed for player " + playerId, failure);
            return false;
        }
    }

    public boolean owns(UUID portal) {
        if (nativeRendererSelected()) {
            return true;
        }
        ViewStreamSlot<B> slot = state == ViewStreamSessionState.CLIENT_VIEW ? slots.get(portal) : null;
        return slot != null && !slot.effects;
    }

    public boolean effectsReceiver() {
        return !closed && state == ViewStreamSessionState.CLIENT_VIEW && effectsSelected(caps);
    }

    public boolean burst(ViewStreamMessage.Extension message) {
        Objects.requireNonNull(message, "message");
        if (!effectsReceiver()) {
            return false;
        }
        if (pendingBursts.incrementAndGet() > MAX_PENDING_BURSTS) {
            pendingBursts.decrementAndGet();
            return false;
        }
        inbox.add(new Burst<B>(message));
        lane.submit();
        return true;
    }

    public boolean offer(ViewStreamPhase phase) {
        Objects.requireNonNull(phase, "phase");
        ViewStreamMessage.Offer offer;
        synchronized (handshakeLock) {
            if (closed || state != ViewStreamSessionState.VANILLA || !registry.enabled()) {
                return false;
            }
            ViewStreamHandshake.Policy policy = policy(phase);
            handshake = new ViewStreamHandshake(policy, registry.codec(), zeroCopyNonce, registry::nextSessionId, registry::hashSalt);
            long now = millis();
            if (brandTag != null) {
                handshake.brand(brandTag, now);
            }
            offer = handshake.offer(now);
            state = ViewStreamSessionState.PENDING;
        }
        sendDirect(offer);
        return true;
    }

    public void brand(String tag) {
        synchronized (handshakeLock) {
            brandTag = tag;
            if (handshake != null) {
                handshake.brand(tag, millis());
            }
            handshakeLock.notifyAll();
        }
    }

    public void pong() {
        synchronized (handshakeLock) {
            if (handshake == null || state != ViewStreamSessionState.PENDING) {
                return;
            }
            if (handshake.onPong(millis()).state() == ViewStreamHandshake.State.VANILLA) {
                state = ViewStreamSessionState.VANILLA;
            }
            handshakeLock.notifyAll();
        }
    }

    public boolean awaitingHello() {
        synchronized (handshakeLock) {
            return state == ViewStreamSessionState.PENDING && handshake != null && handshake.waiting(millis());
        }
    }

    public ViewStreamSessionState expire() {
        synchronized (handshakeLock) {
            if (state == ViewStreamSessionState.PENDING && handshake != null
                && handshake.onDeadline(millis()).state() != ViewStreamHandshake.State.OFFERED) {
                state = ViewStreamSessionState.VANILLA;
                handshakeLock.notifyAll();
            }
            return state;
        }
    }

    public ViewStreamSessionState awaitHandshake() throws InterruptedException {
        synchronized (handshakeLock) {
            while (!closed && state == ViewStreamSessionState.PENDING && handshake != null && handshake.waiting(millis())) {
                long remaining = handshake.deadlineMillis() - millis();
                handshakeLock.wait(Math.max(1L, remaining));
            }
        }
        return expire();
    }

    public void tick(long serverTick) {
        this.serverTick = serverTick;
        ViewStreamSessionState current = state;
        if (current == ViewStreamSessionState.PENDING) {
            expire();
            return;
        }
        if (current != ViewStreamSessionState.CLIENT_VIEW || closed) {
            if (!slotList.isEmpty() || !rejected.isEmpty()) {
                forgetOwned();
            }
            return;
        }
        ViewStreamMessage.ResetReason recoveryReason = recovery.getAndSet(null);
        if (recoveryReason != null) {
            inbox.add(new Reset<>(recoveryReason, false));
            forgetOwned();
        }
        if (!retryAfter.isEmpty()) {
            retryAfter.entrySet().removeIf(entry -> {
                if (serverTick < entry.getValue()) {
                    return false;
                }
                rejected.remove(entry.getKey());
                return true;
            });
        }
        ViewStreamOptions options = registry.options();
        mesh.beginTick();
        ViewStreamEndpoints<O, B> portals = platform.endpoints();
        interest.clear();
        if (ViewStreamCapability.PLATES.in(caps)) {
            portals.interested(player, interest);
        }
        boolean plateless = false;
        for (int i = 0; i < interest.size(); i++) {
            UUID portal = interest.get(i);
            ViewStreamSlot<B> slot = slots.get(portal);
            if (slot == null) {
                slot = attach(portal, portals);
                if (slot == null) {
                    continue;
                }
                plateless |= clientMirror(slot.baseGeometry);
            } else if (slot.effects) {
                if (!promote(slot, portals)) {
                    continue;
                }
                plateless |= clientMirror(slot.baseGeometry);
            }
            slot.lastInterestTick = serverTick;
        }
        if (effectsSelected(caps)) {
            attachEffects(portals, serverTick);
        }
        boolean signal = recoveryReason != null || plateless || lane.stalled();
        long now = platform.nanoClock().getAsLong();
        int grace = options.interestGraceTicks();
        int owned = 0;
        for (int i = slotList.size() - 1; i >= 0; i--) {
            ViewStreamSlot<B> slot = slotList.get(i);
            long seen = slot.effects ? slot.lastEffectTick : slot.lastInterestTick;
            if (slot.failed || serverTick - seen > grace) {
                if (slot.failed && !slot.effects) {
                    reject(slot.portalId);
                }
                detach(i, slot);
                signal = true;
                continue;
            }
            Refresh outcome = refresh(slot, portals, options, serverTick);
            if (outcome == Refresh.REJECT) {
                reject(slot.portalId);
            }
            if (outcome == Refresh.REJECT || outcome == Refresh.DETACH) {
                detach(i, slot);
                signal = true;
                continue;
            }
            if (!slot.effects) {
                owned++;
            }
            signal |= outcome == Refresh.CHANGED || missDue(slot, now) || (slot.standbySlot != null && missDue(slot.standbySlot, now));
        }
        if (!rejected.isEmpty()) {
            rejected.retainAll(interest);
        }
        attended = owned;
        if (signal) {
            lane.submit();
        }
        hooks.tickExtension(player, millis(), sender);
    }

    public void reset(ViewStreamMessage.ResetReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (terminal(reason)) {
            end(reason);
            return;
        }
        if (state != ViewStreamSessionState.CLIENT_VIEW || closed) {
            return;
        }
        inbox.add(new Reset<B>(reason, false));
        if (reason == ViewStreamMessage.ResetReason.TELEPORT) {
            mesh.clear();
            restream();
        } else {
            forgetOwned();
        }
        lane.submit();
    }

    public void end(ViewStreamMessage.ResetReason reason) {
        Objects.requireNonNull(reason, "reason");
        hooks.onReset(player);
        if (nativeRendererSelected() && reason != ViewStreamMessage.ResetReason.DISABLED) {
            recovery.compareAndSet(null, reason);
            return;
        }
        recovery.set(null);
        synchronized (handshakeLock) {
            ViewStreamSessionState previous = state;
            if (previous == ViewStreamSessionState.VANILLA) {
                return;
            }
            state = ViewStreamSessionState.VANILLA;
            handshakeLock.notifyAll();
            if (previous != ViewStreamSessionState.CLIENT_VIEW) {
                return;
            }
        }
        mesh.clear();
        inbox.add(new Reset<B>(reason, true));
        lane.submit();
    }

    public ViewStreamInbound receive(byte[] payload, int offset, int length) {
        if (closed) {
            return ViewStreamInbound.IGNORED;
        }
        long now = millis();
        int messageType = payload != null && offset >= 0 && offset < payload.length && length > 0 ? payload[offset] & 0xFF : -1;
        ViewStreamRateLimiter.Verdict verdict = limiter.admit(now, length, messageType);
        if (verdict != ViewStreamRateLimiter.Verdict.ACCEPT) {
            return rejectInbound(verdict, null);
        }
        ViewStreamMessage message;
        try {
            message = registry.codec().decodeC2S(payload, offset, length);
        } catch (ViewStreamProtocolException malformed) {
            return rejectInbound(limiter.violation(now, messageType, length), malformed);
        }
        return switch (message) {
            case ViewStreamMessage.Hello hello -> onHello(hello, now);
            case ViewStreamMessage.BrickMiss misses -> onMiss(misses);
            case ViewStreamMessage.Ack ack -> onAck(ack);
            case ViewStreamMessage.MeshAck ack -> onMeshAck(ack);
            case ViewStreamMessage.MeshLocal local -> onMeshLocal(local);
            case ViewStreamMessage.MeshCached cached -> onMeshCached(cached);
            case ViewStreamMessage.Extension extension -> hooks.onExtension(player, extension.payload())
                ? ViewStreamInbound.HANDLED : ViewStreamInbound.IGNORED;
            case ViewStreamMessage.ViewStats stats -> onViewStats(stats, now);
            case ViewStreamMessage.PlateRefused refused -> onRefused(refused);
            default -> rejectInbound(limiter.violation(now, messageType, length), null);
        };
    }

    public ViewStreamSessionStats stats() {
        ViewStreamAckWindow active = window;
        return new ViewStreamSessionStats(playerId, sessionId, state, caps, attended, framesSent.get(), bytesSent.get(),
            groupsSent.get(), active == null ? 0 : active.outstanding(), active == null ? 0L : active.acked(),
            active == null ? 0L : active.lastRttNanos() / 1000L, active == null ? 0L : active.appliedCells(), limiter.admitted(),
            c2sDropped.get(), c2sStale.get(), staleMisses.get(), lateSwitches.get(), viewStats);
    }

    void close() {
        hooks.onClose(player);
        synchronized (handshakeLock) {
            closed = true;
            state = ViewStreamSessionState.VANILLA;
            handshakeLock.notifyAll();
        }
        inbox.clear();
    }

    void drain() {
        if (closed) {
            inbox.clear();
            return;
        }
        long now = platform.nanoClock().getAsLong();
        Command<B> command;
        while ((command = inbox.poll()) != null) {
            switch (command) {
                case Open<B> open -> openLane(open);
                case Attach<B> attach -> attachLane(attach.slot());
                case Detach<B> detach -> detachLane(detach.slot());
                case Miss<B> miss -> answerMiss(miss.miss(), now);
                case Refused<B> refused -> refuseLane(refused.portalKey(), refused.revision());
                case Scene<B> scene -> laneScene.add(scene);
                case Burst<B> burst -> {
                    pendingBursts.decrementAndGet();
                    laneBursts.add(burst.message());
                }
                case Reset<B> reset -> resetLane(reset.reason(), reset.terminal());
            }
        }
        if (laneOpen) {
            for (int i = 0; i < laneSlots.size(); i++) {
                if (!reconcile(laneSlots.get(i), now)) {
                    break;
                }
            }
            sendMesh();
            sendScene();
            sendBursts();
        } else {
            laneScene.clear();
            laneBursts.clear();
        }
        if (laneSent) {
            laneSent = false;
            platform.transport().flush(player);
        }
    }

    private void drainSafely() {
        try {
            drain();
        } catch (RuntimeException failure) {
            platform.warnings().accept("View stream lane failed for " + playerId, failure);
        }
    }

    private ViewStreamHandshake.Policy policy(ViewStreamPhase phase) {
        ViewStreamOptions options = registry.options();
        int grace = phase == ViewStreamPhase.PLAY ? PLAY_PHASE_GRACE_MILLIS : options.helloGraceMillis();
        long serverCaps = (options.serverCaps(phase) | registry.codec().capabilities()) & platform.platformCaps() & ~options.withheldCaps();
        return new ViewStreamHandshake.Policy(registry.enabled(), platform.mcDataVersion(), serverCaps, options.maxFrameBytes(), grace,
            ViewStreamLimits.DEFAULT_TICK_RATE, options.ackWindowFrames(), options.zeroCopy());
    }

    private boolean effectsSelected(long sessionCaps) {
        long required = platform.scene().effectCapability();
        return required != ViewStreamCapability.NONE && (sessionCaps & required) == required;
    }

    private ViewStreamInbound onHello(ViewStreamMessage.Hello hello, long now) {
        if (nativeRendererSelected() && hello.wire() == ViewStreamLimits.WIRE_VERSION
            && hello.mcDataVersion() == platform.mcDataVersion() && ViewStreamCapability.MESH_RENDER.in(hello.clientCaps())) {
            recovery.compareAndSet(null, ViewStreamMessage.ResetReason.PROTOCOL);
            return ViewStreamInbound.HANDLED;
        }
        ViewStreamHandshake.Result result;
        synchronized (handshakeLock) {
            if (handshake == null || state == ViewStreamSessionState.CLIENT_VIEW) {
                return stale();
            }
            result = handshake.onHello(hello, now, true);
            if (result.reply() == null) {
                return stale();
            }
            sendDirect(result.reply());
            if (!result.accepted()) {
                state = ViewStreamSessionState.VANILLA;
                handshakeLock.notifyAll();
                return ViewStreamInbound.HELLO_DECLINED;
            }
            ViewStreamMessage.Accept accept = handshake.accepted();
            caps = accept.caps();
            sessionId = accept.sessionId();
            window = new ViewStreamAckWindow(accept.ackWindowFrames());
            inbox.add(new Open<B>(accept));
            if (result.late()) {
                lateSwitches.incrementAndGet();
            }
            state = ViewStreamSessionState.CLIENT_VIEW;
            handshakeLock.notifyAll();
        }
        lane.submit();
        return ViewStreamInbound.HELLO_ACCEPTED;
    }

    private ViewStreamInbound onMiss(ViewStreamMessage.BrickMiss misses) {
        if (state != ViewStreamSessionState.CLIENT_VIEW || !ViewStreamCapability.BRICK_CACHE.in(caps)) {
            return stale();
        }
        List<ViewStreamMessage.BrickMiss.Plate> plates = misses.plates();
        for (int i = 0; i < plates.size(); i++) {
            inbox.add(new Miss<B>(plates.get(i)));
        }
        lane.submit();
        return ViewStreamInbound.HANDLED;
    }

    private ViewStreamInbound onRefused(ViewStreamMessage.PlateRefused refused) {
        if (state != ViewStreamSessionState.CLIENT_VIEW) {
            return stale();
        }
        if (ViewStreamCapability.MESH_RENDER.in(caps) && mesh.staleRefusal(refused.portalKey(), refused.plateRevision())) {
            return stale();
        }
        inbox.add(new Refused<B>(refused.portalKey(), refused.plateRevision()));
        lane.submit();
        return ViewStreamInbound.HANDLED;
    }

    private ViewStreamInbound onMeshAck(ViewStreamMessage.MeshAck ack) {
        if (state != ViewStreamSessionState.CLIENT_VIEW || !ViewStreamCapability.MESH_RENDER.in(caps)) {
            return stale();
        }
        return mesh.acknowledge(ack) ? ViewStreamInbound.HANDLED : stale();
    }

    private ViewStreamInbound onMeshLocal(ViewStreamMessage.MeshLocal local) {
        if (state != ViewStreamSessionState.CLIENT_VIEW || !ViewStreamCapability.LOCAL_MESH.in(caps)
            || !ViewStreamCapability.MESH_RENDER.in(caps)) {
            return stale();
        }
        List<UUID> entities = new ArrayList<>(local.entities().size());
        for (UUID source : local.entities()) {
            entities.add(platform.entities().projectedId(source));
        }
        ViewStreamMessage.MeshLocal projected = new ViewStreamMessage.MeshLocal(local.portalKey(), local.generation(), local.sequence(),
            local.available(), local.sections(), entities);
        return mesh.local(projected) ? ViewStreamInbound.HANDLED : stale();
    }

    private ViewStreamInbound onMeshCached(ViewStreamMessage.MeshCached cached) {
        if (state != ViewStreamSessionState.CLIENT_VIEW || !ViewStreamCapability.MESH_REUSE.in(caps)
            || !ViewStreamCapability.MESH_RENDER.in(caps)) {
            return stale();
        }
        return mesh.cached(cached) ? ViewStreamInbound.HANDLED : stale();
    }

    private ViewStreamInbound onAck(ViewStreamMessage.Ack ack) {
        ViewStreamAckWindow active = window;
        if (state != ViewStreamSessionState.CLIENT_VIEW || active == null) {
            return stale();
        }
        if (active.ack(ack.seq(), ack.appliedCells(), platform.nanoClock().getAsLong()) && paused) {
            paused = false;
            lane.submit();
        }
        return ViewStreamInbound.HANDLED;
    }

    private ViewStreamInbound onViewStats(ViewStreamMessage.ViewStats stats, long now) {
        if (state != ViewStreamSessionState.CLIENT_VIEW || !ViewStreamCapability.VIEW_STATS.in(caps) || !registry.options().viewStats()
            || (viewStats != null && now - viewStatsMillis < ViewStreamLimits.VIEW_STATS_MIN_INTERVAL_MILLIS - ViewStreamLimits.VIEW_STATS_JITTER_MILLIS)) {
            return stale();
        }
        viewStats = stats;
        viewStatsMillis = now;
        return ViewStreamInbound.HANDLED;
    }

    private ViewStreamInbound stale() {
        c2sStale.incrementAndGet();
        return ViewStreamInbound.IGNORED;
    }

    private ViewStreamInbound rejectInbound(ViewStreamRateLimiter.Verdict verdict, Throwable cause) {
        c2sDropped.incrementAndGet();
        if (verdict == ViewStreamRateLimiter.Verdict.RESET) {
            platform.warnings().accept("View stream protocol reset for player " + playerId + ": " + limiter.lastViolation(), cause);
            end(ViewStreamMessage.ResetReason.PROTOCOL);
            return ViewStreamInbound.RESET;
        }
        return ViewStreamInbound.DROPPED;
    }

    private void reject(UUID portal) {
        rejected.add(portal);
        if (nativeRendererSelected()) {
            retryAfter.put(portal, serverTick + NATIVE_RETRY_TICKS);
        }
    }

    private ViewStreamSlot<B> attach(UUID portal, ViewStreamEndpoints<O, B> portals) {
        if (rejected.contains(portal)) {
            return null;
        }
        long stamp = portals.geometryRevision(player, portal);
        ApertureDescriptor geometry = ownable(portal, portals);
        if (geometry == null) {
            return null;
        }
        ViewStreamSlot<B> slot = new ViewStreamSlot<B>(portal, nextPortalKey++, false);
        own(slot, stamp, geometry, portals);
        slots.put(portal, slot);
        slotList.add(slot);
        inbox.add(new Attach<B>(slot));
        return slot;
    }

    private boolean promote(ViewStreamSlot<B> slot, ViewStreamEndpoints<O, B> portals) {
        if (rejected.contains(slot.portalId)) {
            return false;
        }
        long stamp = portals.geometryRevision(player, slot.portalId);
        ApertureDescriptor geometry = ownable(slot.portalId, portals);
        if (geometry == null) {
            return false;
        }
        own(slot, stamp, geometry, portals);
        return true;
    }

    private ApertureDescriptor ownable(UUID portal, ViewStreamEndpoints<O, B> portals) {
        ApertureDescriptor geometry = meshGeometry(portals.geometry(player, portal, registry.palette()), portals);
        if (geometry == null) {
            reject(portal);
            return null;
        }
        return meshEnabled() || clientMirror(geometry) || !portals.refused(player, portal) ? geometry : null;
    }

    private void own(ViewStreamSlot<B> slot, long stamp, ApertureDescriptor geometry, ViewStreamEndpoints<O, B> portals) {
        slot.effects = false;
        slot.geometryStamp = stamp;
        slot.baseGeometry = geometry;
        slot.geometry = geometry;
        slot.needFullEntities = true;
        slot.needFullScene = true;
        portals.releaseVanilla(player, slot.portalId);
    }

    private void attachEffects(ViewStreamEndpoints<O, B> portals, long serverTick) {
        effectInterest.clear();
        portals.effects(player, effectInterest);
        for (int i = 0; i < effectInterest.size(); i++) {
            UUID portal = effectInterest.get(i);
            ViewStreamSlot<B> slot = slots.get(portal);
            if (slot == null) {
                long stamp = portals.effectGeometryRevision(player, portal);
                ApertureDescriptor geometry = portals.effectGeometry(player, portal, registry.palette());
                if (geometry == null) {
                    continue;
                }
                slot = new ViewStreamSlot<B>(portal, nextPortalKey++, false);
                slot.effects = true;
                slot.geometryStamp = stamp;
                slot.baseGeometry = geometry;
                slot.geometry = geometry;
                slot.needFullScene = true;
                slots.put(portal, slot);
                slotList.add(slot);
                inbox.add(new Attach<B>(slot));
            }
            slot.lastEffectTick = serverTick;
        }
    }

    private void detach(int index, ViewStreamSlot<B> slot) {
        mesh.remove(slot.key);
        slotList.remove(index);
        slots.remove(slot.portalId);
        inbox.add(new Detach<B>(slot));
        detachChildren(slot);
        platform.endpoints().releaseNested(player, slot.contextId);
        if (slot.standbySlot != null) {
            inbox.add(new Detach<B>(slot.standbySlot));
            slot.standbySlot = null;
        }
    }

    private void detachChildren(ViewStreamSlot<B> slot) {
        for (int i = 0; i < slot.children.size(); i++) {
            detachChild(slot.children.get(i));
        }
        slot.children.clear();
    }

    private void detachChild(ViewStreamSlot<B> child) {
        detachChildren(child);
        platform.endpoints().releaseNested(player, child.contextId);
        mesh.remove(child.key);
        inbox.add(new Detach<B>(child));
    }

    private void restream() {
        for (int i = 0; i < slotList.size(); i++) {
            detachChildren(slotList.get(i));
            ViewStreamSlot<B> successor = slotList.get(i).successor(nextPortalKey++);
            slotList.set(i, successor);
            slots.put(successor.portalId, successor);
            inbox.add(new Attach<B>(successor));
        }
    }

    private void forgetOwned() {
        for (ViewStreamSlot<B> slot : slotList) {
            releaseContexts(slot);
        }
        mesh.clear();
        slots.clear();
        slotList.clear();
        rejected.clear();
        retryAfter.clear();
        attended = 0;
    }

    private void releaseContexts(ViewStreamSlot<B> slot) {
        for (ViewStreamSlot<B> child : slot.children) {
            releaseContexts(child);
        }
        platform.endpoints().releaseNested(player, slot.contextId);
    }

    private Refresh refresh(ViewStreamSlot<B> slot, ViewStreamEndpoints<O, B> portals, ViewStreamOptions options, long serverTick) {
        if (slot.effects) {
            return refreshEffects(slot, portals, options, serverTick);
        }
        boolean changed = false;
        boolean geometryChanged = false;
        long stamp = portals.geometryRevision(player, slot.portalId);
        int meshDepth = meshEnabled() ? portals.meshDistanceBlocks(player) : 0;
        if (stamp != slot.geometryStamp || meshDepth > 0 && slot.baseGeometry.depthBlocks() != meshDepth) {
            ApertureDescriptor geometry = meshGeometry(portals.geometry(player, slot.portalId, registry.palette()), portals);
            if (geometry == null) {
                return Refresh.REJECT;
            }
            slot.geometryStamp = stamp;
            if (!geometry.equals(slot.baseGeometry)) {
                slot.baseGeometry = geometry;
                geometryChanged = true;
            }
        }
        if (!meshEnabled() && !clientMirror(slot.baseGeometry)) {
            ViewPlate<B> plate = portals.plate(player, slot.portalId, slot.observedPlate == null);
            if (plate == null) {
                if (portals.refused(player, slot.portalId)) {
                    return Refresh.DETACH;
                }
            } else if (plate != slot.observedPlate) {
                slot.observedPlate = plate;
                slot.target = new ViewStreamSlot.PlateTarget<B>(plate, light(slot.portalId, plate, portals, options));
                changed = true;
            }
        }
        int nested = refreshNested(slot, portals, options, serverTick);
        if (geometryChanged || (nested & NESTED_GEOMETRY) != 0) {
            ApertureDescriptor composed = compose(slot);
            if (!composed.equals(slot.geometry)) {
                slot.geometry = composed;
            }
        }
        changed |= geometryChanged || nested != 0;
        if (!meshEnabled()) {
            changed |= refreshStandby(slot, portals, options);
        } else {
            if (platform.scene().environmentUnavailable(player, null, slot.portalId)) {
                mesh.remove(slot.key);
                return Refresh.REJECT;
            }
            try {
                changed |= mesh.refresh(slot, portals, player, serverTick, platform.nanoClock().getAsLong(),
                    true, portals.meshEye(player));
            } catch (IllegalStateException failure) {
                platform.warnings().accept("View stream mesh failed for endpoint " + slot.portalId + " and player " + playerId, failure);
                mesh.remove(slot.key);
                return Refresh.REJECT;
            }
        }
        changed |= refreshScene(slot, options, serverTick);
        return changed ? Refresh.CHANGED : Refresh.UNCHANGED;
    }

    private Refresh refreshEffects(ViewStreamSlot<B> slot, ViewStreamEndpoints<O, B> portals, ViewStreamOptions options,
                                   long serverTick) {
        boolean changed = false;
        long stamp = portals.effectGeometryRevision(player, slot.portalId);
        if (stamp != slot.geometryStamp) {
            ApertureDescriptor geometry = portals.effectGeometry(player, slot.portalId, registry.palette());
            if (geometry == null) {
                return Refresh.DETACH;
            }
            slot.geometryStamp = stamp;
            slot.baseGeometry = geometry;
            slot.geometry = geometry;
            changed = true;
        }
        changed |= refreshScene(slot, options, serverTick);
        return changed ? Refresh.CHANGED : Refresh.UNCHANGED;
    }

    private boolean refreshStandby(ViewStreamSlot<B> slot, ViewStreamEndpoints<O, B> portals, ViewStreamOptions options) {
        ViewStreamSlot<B> shadow = slot.standbySlot;
        if (!options.standbyPrestream()) {
            if (shadow == null) {
                return false;
            }
            slot.standbySlot = null;
            inbox.add(new Detach<B>(shadow));
            return true;
        }
        ViewPlate<B> standby = portals.standbyPlate(player, slot.portalId);
        if (standby == null || (shadow != null && standby == shadow.observedPlate)) {
            return false;
        }
        if (shadow == null) {
            shadow = new ViewStreamSlot<B>(slot.portalId, nextPortalKey++, true);
            slot.standbySlot = shadow;
            inbox.add(new Attach<B>(shadow));
        }
        shadow.observedPlate = standby;
        shadow.target = new ViewStreamSlot.PlateTarget<B>(standby, light(slot.portalId, standby, portals, options));
        return true;
    }

    private boolean refreshScene(ViewStreamSlot<B> slot, ViewStreamOptions options, long serverTick) {
        long sessionCaps = caps;
        boolean queued = false;
        if (!slot.effects && options.entityFrames() && ViewStreamCapability.ENTITY_FRAMES.in(sessionCaps)) {
            ViewStreamMessage.EntityFrame frame = platform.entities().frame(player, slot.portalId, slot.key, serverTick, slot.needFullEntities,
                clientMirror(slot.baseGeometry));
            if (frame != null) {
                slot.needFullEntities = false;
                inbox.add(new Scene<B>(slot, mesh.localEntities(frame)));
                queued = true;
            }
            if (ViewStreamCapability.ENTITY_EVENTS.in(sessionCaps)) {
                List<ViewStreamMessage.EntityEvent> events = platform.entities().events(player, slot.portalId, slot.key);
                for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
                    if (mesh.localEntity(slot.key, events.get(eventIndex).entityId())) {
                        continue;
                    }
                    inbox.add(new Scene<B>(slot, events.get(eventIndex)));
                    queued = true;
                }
            }
        }
        boolean fullScene = slot.needFullScene;
        if (!slot.effects && meshEnabled()) {
            ViewStreamMessage.Environment environment = platform.scene().environment(player, slot.portalId, slot.key, serverTick, fullScene);
            if (environment != null) {
                inbox.add(new Scene<B>(slot, environment));
                queued = true;
            }
        }
        slot.needFullScene = false;
        if (effectsSelected(sessionCaps)) {
            ViewStreamMessage.Extension effects = platform.scene().effects(player, slot.portalId, slot.key, serverTick, fullScene);
            if (effects != null) {
                inbox.add(new Scene<B>(slot, effects));
                queued = true;
            }
        }
        if (!slot.effects && ViewStreamCapability.ATMOSPHERE.in(sessionCaps)) {
            ViewStreamMessage.Atmosphere atmosphere = platform.scene().atmosphere(player, slot.portalId, slot.key, serverTick, fullScene);
            if (atmosphere != null) {
                inbox.add(new Scene<B>(slot, atmosphere));
                queued = true;
            }
        }
        return queued;
    }

    private int refreshNested(ViewStreamSlot<B> slot, ViewStreamEndpoints<O, B> portals, ViewStreamOptions options, long serverTick) {
        if (meshEnabled()) {
            portals.prepareNested(player, slot.contextId, null, slot.portalId);
            int depth = nativeRecursionDepth(slot.baseGeometry);
            return refreshNativeNested(slot, portals, serverTick, 0, depth, slot.baseGeometry.mirror() ? 1 : 0, new int[]{ViewStreamLimits.MAX_NESTED_GEOMETRY});
        }
        if (!clientRecursion(slot.baseGeometry)) {
            if (slot.children.isEmpty()) {
                return 0;
            }
            detachChildren(slot);
            return NESTED_GEOMETRY;
        }
        nestedScratch.clear();
        portals.nested(player, slot.portalId, slot.baseGeometry, nestedScratch);
        int limit = Math.min(nestedScratch.size(), ViewStreamLimits.MAX_NESTED_GEOMETRY);
        int flags = 0;
        for (int i = slot.children.size() - 1; i >= 0; i--) {
            ViewStreamSlot<B> child = slot.children.get(i);
            int index = nestedScratch.indexOf(child.childId);
            if (child.failed || index < 0 || index >= limit) {
                slot.children.remove(i);
                mesh.remove(child.key);
                inbox.add(new Detach<B>(child));
                flags |= NESTED_GEOMETRY;
            }
        }
        for (int i = 0; i < limit; i++) {
            UUID childId = nestedScratch.get(i);
            ViewStreamSlot<B> child = slot.child(childId);
            long stamp = portals.nestedGeometryRevision(player, slot.portalId, childId);
            if (child == null || stamp != child.geometryStamp) {
                ApertureDescriptor geometry = meshGeometry(portals.nestedGeometry(player, slot.portalId, childId, registry.palette()), portals);
                if (geometry == null || !geometry.nested().isEmpty()) {
                    if (child != null) {
                        slot.children.remove(child);
                        mesh.remove(child.key);
                        inbox.add(new Detach<B>(child));
                        flags |= NESTED_GEOMETRY;
                    }
                    continue;
                }
                if (child == null) {
                    child = new ViewStreamSlot<B>(slot.portalId, nextPortalKey++, false, childId);
                    slot.children.add(child);
                    inbox.add(new Attach<B>(child));
                }
                child.geometryStamp = stamp;
                child.baseGeometry = geometry;
                child.geometry = geometry.withParent(slot.key);
                flags |= NESTED_GEOMETRY;
            }
            ViewPlate<B> plate = portals.nestedPlate(player, slot.portalId, childId);
            if (plate != null && plate != child.observedPlate) {
                child.observedPlate = plate;
                child.target = new ViewStreamSlot.PlateTarget<B>(plate, light(childId, plate, portals, options));
                flags |= NESTED_PLATE;
            }
        }
        return flags;
    }

    private int refreshNativeNested(ViewStreamSlot<B> slot, ViewStreamEndpoints<O, B> portals, long serverTick,
                                    int depth, int depthLimit, int mirrors, int[] remaining) {
        if (depth >= depthLimit || !clientRecursion(slot.baseGeometry) || remaining[0] <= 0) {
            boolean changed = !slot.children.isEmpty();
            detachChildren(slot);
            return changed ? NESTED_GEOMETRY : 0;
        }
        List<UUID> candidates = new ArrayList<>(4);
        portals.nested(player, slot.contextId, slot.baseGeometry, candidates);
        UUID actualParent = slot.nestedChild() ? slot.childId : slot.portalId;
        candidates.removeIf(actualParent::equals);
        int limit = Math.min(candidates.size(), remaining[0]);
        int flags = 0;
        for (int index = slot.children.size() - 1; index >= 0; index--) {
            ViewStreamSlot<B> child = slot.children.get(index);
            int position = candidates.indexOf(child.childId);
            if (child.failed || position < 0 || position >= limit) {
                if (child.failed) {
                    retryAfter.put(child.contextId, serverTick + NATIVE_RETRY_TICKS);
                }
                slot.children.remove(index);
                detachChild(child);
                flags |= NESTED_GEOMETRY;
            }
        }
        Set<UUID> visited = new HashSet<>();
        for (int index = 0; index < limit && remaining[0] > 0; index++) {
            UUID childId = candidates.get(index);
            if (retryAfter.containsKey(ViewStreamSlot.contextId(slot.contextId, childId))) {
                continue;
            }
            ViewStreamSlot<B> child = slot.child(childId);
            boolean created = child == null;
            if (created) {
                child = new ViewStreamSlot<>(slot.contextId, nextPortalKey++, false, childId);
                child.needFullEntities = true;
                child.needFullScene = true;
            }
            portals.prepareNested(player, child.contextId, slot.contextId, childId);
            long stamp = portals.nestedGeometryRevision(player, slot.contextId, childId);
            if (child.baseGeometry == null || child.geometryStamp != stamp
                || child.baseGeometry.depthBlocks() != portals.meshDistanceBlocks(player)) {
                ApertureDescriptor geometry = meshGeometry(portals.nestedGeometry(player, slot.contextId, childId, registry.palette()), portals);
                if (geometry == null) {
                    slot.children.remove(child);
                    if (created) {
                        portals.releaseNested(player, child.contextId);
                    } else {
                        detachChild(child);
                    }
                    flags |= NESTED_GEOMETRY;
                    continue;
                }
                child.geometryStamp = stamp;
                if (!geometry.equals(child.baseGeometry)) {
                    child.baseGeometry = geometry;
                    flags |= NESTED_GEOMETRY;
                }
            }
            int childMirrors = mirrors + (child.baseGeometry.mirror() ? 1 : 0);
            if (childMirrors > ViewStreamLimits.MAX_MIRROR_REFLECTIONS) {
                slot.children.remove(child);
                if (created) {
                    portals.releaseNested(player, child.contextId);
                } else {
                    detachChild(child);
                }
                flags |= NESTED_GEOMETRY;
                continue;
            }
            visited.add(childId);
            if (created) {
                child.geometry = child.baseGeometry.withParent(slot.key);
                slot.children.add(child);
                inbox.add(new Attach<>(child));
            }
            remaining[0]--;
            int descendants = refreshNativeNested(child, portals, serverTick, depth + 1,
                Math.min(depthLimit, depth + 1 + nativeRecursionDepth(child.baseGeometry)), childMirrors, remaining);
            ApertureDescriptor composed = compose(child).withParent(slot.key);
            if (!composed.equals(child.geometry)) {
                child.geometry = composed;
                flags |= NESTED_GEOMETRY;
            }
            flags |= descendants;
            if (platform.scene().environmentUnavailable(player, slot.contextId, childId)) {
                child.failed = true;
                mesh.remove(child.key);
                flags |= NESTED_PLATE;
                continue;
            }
            if (registry.options().entityFrames() && ViewStreamCapability.ENTITY_FRAMES.in(caps)) {
                ViewStreamMessage.EntityFrame entities = platform.entities().frame(player, child.contextId, child.key, serverTick,
                    child.needFullEntities, clientMirror(child.baseGeometry));
                if (entities != null) {
                    child.needFullEntities = false;
                    inbox.add(new Scene<>(child, mesh.localEntities(entities)));
                    flags |= NESTED_PLATE;
                }
                if (ViewStreamCapability.ENTITY_EVENTS.in(caps)) {
                    List<ViewStreamMessage.EntityEvent> events = platform.entities().events(player, child.contextId, child.key);
                    for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
                        if (mesh.localEntity(child.key, events.get(eventIndex).entityId())) {
                            continue;
                        }
                        inbox.add(new Scene<B>(child, events.get(eventIndex)));
                        flags |= NESTED_PLATE;
                    }
                }
            }
            ViewStreamMessage.Environment environment = platform.scene().nestedEnvironment(player, slot.contextId, childId, child.key,
                serverTick, child.needFullScene);
            if (environment != null) {
                inbox.add(new Scene<>(child, environment));
                flags |= NESTED_PLATE;
            }
            child.needFullScene = false;
            Vec3d eye = portals.nestedEye(player, slot.contextId);
            if (eye != null) {
                try {
                    if (mesh.refresh(child, portals, player, serverTick, platform.nanoClock().getAsLong(), true, eye)) {
                        flags |= NESTED_PLATE;
                    }
                } catch (IllegalStateException failure) {
                    child.failed = true;
                    mesh.remove(child.key);
                    platform.warnings().accept("View stream nested mesh failed for endpoint " + childId + " and player " + playerId, failure);
                }
            }
        }
        for (int index = slot.children.size() - 1; index >= 0; index--) {
            ViewStreamSlot<B> child = slot.children.get(index);
            if (!visited.contains(child.childId)) {
                slot.children.remove(index);
                detachChild(child);
                flags |= NESTED_GEOMETRY;
            }
        }
        return flags;
    }

    private static <B> ApertureDescriptor compose(ViewStreamSlot<B> slot) {
        if (slot.children.isEmpty()) {
            return slot.baseGeometry;
        }
        List<ApertureDescriptor> nested = new ArrayList<ApertureDescriptor>(slot.children.size());
        for (int i = 0; i < slot.children.size(); i++) {
            nested.add(slot.children.get(i).geometry);
        }
        return slot.baseGeometry.withNested(nested);
    }

    private boolean meshEnabled() {
        return ViewStreamCapability.MESH_RENDER.in(caps) && platform.endpoints().meshDistanceBlocks(player) > 0;
    }

    private ApertureDescriptor meshGeometry(ApertureDescriptor geometry, ViewStreamEndpoints<O, B> portals) {
        return geometry != null && meshEnabled() ? geometry.withDepth(portals.meshDistanceBlocks(player)) : geometry;
    }

    private boolean clientMirror(ApertureDescriptor geometry) {
        return geometry != null && geometry.mirror() && ViewStreamCapability.CLIENT_MIRROR.in(caps) && registry.options().clientMirror();
    }

    private static int nativeRecursionDepth(ApertureDescriptor geometry) {
        if (geometry.recursionDepth() <= 0) {
            return 0;
        }
        return geometry.mirror() ? ViewStreamLimits.MAX_GEOMETRY_DEPTH - 1
            : Math.min(geometry.recursionDepth(), ViewStreamLimits.MAX_LINKED_GEOMETRY_DEPTH - 1);
    }

    private boolean clientRecursion(ApertureDescriptor geometry) {
        return geometry != null && geometry.recursionDepth() > 0 && ViewStreamCapability.CLIENT_RECURSION.in(caps)
            && registry.options().clientRecursion();
    }

    private BrickLightSource light(UUID portal, ViewPlate<B> plate, ViewStreamEndpoints<O, B> portals, ViewStreamOptions options) {
        if (!options.destinationLight() || !ViewStreamCapability.DEST_LIGHT.in(caps)) {
            return BrickLightSource.NONE;
        }
        BrickLightSource light = portals.lightBaseline(player, portal, plate);
        return light == null ? BrickLightSource.NONE : light;
    }

    private static boolean missDue(ViewStreamSlot<?> slot, long now) {
        long deadline = slot.missDeadlineNanos;
        return deadline != 0L && now - deadline >= 0L;
    }

    private void openLane(Open<B> open) {
        laneSlots.clear();
        laneScene.clear();
        cursor = registry.palette().cursor();
        laneCaps = open.accept().caps();
        entitySelfPending = ViewStreamCapability.ENTITY_SELF.in(laneCaps);
        splitter = new FrameSplitter(open.accept().maxFrameBytes(), ViewStreamCapability.LINK_UNCOMPRESSED.in(laneCaps), registry.codec());
        laneOpen = true;
    }

    private void attachLane(ViewStreamSlot<B> slot) {
        if (!laneOpen) {
            return;
        }
        slot.laneAttached = true;
        laneSlots.add(slot);
    }

    private void detachLane(ViewStreamSlot<B> slot) {
        if (!slot.laneAttached) {
            return;
        }
        slot.laneAttached = false;
        slot.awaitingEncoded = null;
        slot.missDeadlineNanos = 0L;
        abandonWindow(slot);
        laneSlots.remove(slot);
        if (slot.announced && laneOpen) {
            emitQuietly(List.of(new ViewStreamMessage.PortalDrop(slot.key)));
        }
    }

    private void resetLane(ViewStreamMessage.ResetReason reason, boolean terminal) {
        if (!laneOpen) {
            return;
        }
        for (int i = 0; i < laneSlots.size(); i++) {
            ViewStreamSlot<B> slot = laneSlots.get(i);
            slot.laneAttached = false;
            slot.awaitingEncoded = null;
            slot.missDeadlineNanos = 0L;
            slot.windowOpen = false;
        }
        laneSlots.clear();
        laneScene.clear();
        laneBursts.clear();
        cursor.reset();
        entitySelfPending = ViewStreamCapability.ENTITY_SELF.in(laneCaps);
        ViewStreamAckWindow active = window;
        if (active != null) {
            active.clear();
        }
        paused = false;
        emitQuietly(List.of(new ViewStreamMessage.SessionReset(reason)));
        if (terminal) {
            laneOpen = false;
        }
    }

    private boolean reconcile(ViewStreamSlot<B> slot, long now) {
        if (slot.awaitingMiss()) {
            if (now - slot.missDeadlineNanos < 0L) {
                return true;
            }
            completeStream(slot, null, now);
        }
        ApertureDescriptor geometry = slot.geometry;
        ViewStreamSlot.PlateTarget<B> target = slot.target;
        boolean geometryDue = !slot.standby && geometry != null && geometry != slot.sentGeometry;
        boolean plateDue = target != null && target.plate() != slot.sentPlate && (slot.standby || slot.announced || geometryDue);
        if (!geometryDue && !plateDue) {
            return true;
        }
        if (windowFull()) {
            return false;
        }
        try {
            stream(slot, geometryDue ? geometry : null, plateDue ? target : null, now);
        } catch (ViewStreamProtocolException | IllegalArgumentException | IllegalStateException failure) {
            slot.failed = true;
            cursor.reset();
            platform.warnings().accept("View stream failed for endpoint " + slot.portalId + " and player " + playerId, failure);
        }
        return true;
    }

    private boolean windowFull() {
        ViewStreamAckWindow active = window;
        if (active == null || !active.full()) {
            return false;
        }
        paused = true;
        if (active.full()) {
            return true;
        }
        paused = false;
        return false;
    }

    private void stream(ViewStreamSlot<B> slot, ApertureDescriptor geometry, ViewStreamSlot.PlateTarget<B> target, long now)
        throws ViewStreamProtocolException {
        List<ViewStreamMessage> group = new ArrayList<ViewStreamMessage>(6);
        List<ViewStreamMessage.PaletteEntry> entries = new ArrayList<ViewStreamMessage.PaletteEntry>();
        int geometryRevision = slot.geometryRevision;
        if (geometry != null) {
            entries.addAll(cursor.pending(geometryPaletteIds(geometry)));
            geometryRevision++;
            group.add(new ViewStreamMessage.Portal(slot.key, geometryRevision, geometry));
        }
        boolean close = true;
        EncodedPlate encoded = null;
        int plateRevision = slot.plateRevision;
        if (target != null) {
            plateRevision++;
            long handle = ViewStreamCapability.ZERO_COPY.in(laneCaps)
                ? platform.handoffs().publish(slot.key, plateRevision, target.plate(), target.light())
                : 0L;
            if (handle != 0L) {
                group.add(new ViewStreamMessage.PlateHandle(slot.key, plateRevision, handle));
            } else {
                encoded = registry.encoderFor(target.light()).encode(target.plate(), target.light(), true);
                entries.addAll(cursor.pending(encoded.referencedIds()));
                EncodedPlate previous = slot.sentEncoded;
                if (previous != null && slot.sentPlate.key().equals(target.plate().key()) && previous.sections().equals(encoded.sections())
                    && previous.cells().equals(encoded.cells())) {
                    ViewStreamMessage.PlatePatch patch = PlatePatchEncoder.diff(slot.key, slot.plateRevision, plateRevision, previous, encoded);
                    if (patch.ops().isEmpty()) {
                        plateRevision = slot.plateRevision;
                    } else {
                        group.add(patch);
                    }
                } else if (ViewStreamCapability.BRICK_CACHE.in(laneCaps) && hashManifestFits(slot.key, encoded)) {
                    group.add(encoded.begin(slot.key, plateRevision, true, registry.hashSalt()));
                    close = false;
                } else {
                    group.add(encoded.begin(slot.key, plateRevision, false, 0L));
                    group.add(encoded.bricksMessage(slot.key, plateRevision));
                    group.add(encoded.end(slot.key, plateRevision));
                }
            }
        }
        if (!entries.isEmpty()) {
            group.add(0, new ViewStreamMessage.Palette(entries));
        }
        if (!group.isEmpty()) {
            int last = emit(group, close);
            ViewStreamAckWindow active = window;
            if (active != null && close) {
                active.record(last, now);
            } else if (active != null) {
                active.open(last, now);
                slot.windowOpen = true;
                slot.windowSequence = last;
            }
        }
        if (geometry != null) {
            slot.sentGeometry = geometry;
            slot.geometryRevision = geometryRevision;
            slot.announced = true;
        }
        if (target != null) {
            slot.sentPlate = target.plate();
            slot.sentEncoded = encoded;
            slot.plateRevision = plateRevision;
            slot.announced = true;
            if (!close) {
                slot.awaitingEncoded = encoded;
                slot.awaitingRevision = plateRevision;
                slot.missDeadlineNanos = now + BRICK_MISS_TIMEOUT_NANOS;
            }
        }
    }

    private boolean hashManifestFits(int portalKey, EncodedPlate encoded) throws ViewStreamProtocolException {
        int headerBytes = ViewStreamCodec.projectionBody(encoded.begin(portalKey, 0, false, 0L)).length;
        long frameBytes = ViewStreamLimits.S2C_HEADER_BYTES + headerBytes + (long) Long.BYTES * encoded.brickCount();
        return frameBytes <= splitter.maxFrameBytes();
    }

    private void answerMiss(ViewStreamMessage.BrickMiss.Plate miss, long now) {
        for (int i = 0; i < laneSlots.size(); i++) {
            ViewStreamSlot<B> slot = laneSlots.get(i);
            if (slot.key == miss.portalKey() && slot.awaitingMiss() && slot.awaitingRevision == miss.plateRevision()) {
                completeStream(slot, miss, now);
                return;
            }
        }
        staleMisses.incrementAndGet();
    }

    private void refuseLane(int portalKey, int revision) {
        if (ViewStreamCapability.MESH_RENDER.in(laneCaps) && mesh.staleRefusal(portalKey, revision)) {
            return;
        }
        for (int i = 0; i < laneSlots.size(); i++) {
            ViewStreamSlot<B> slot = laneSlots.get(i);
            if (slot.key == portalKey) {
                slot.awaitingEncoded = null;
                slot.missDeadlineNanos = 0L;
                slot.failed = true;
                mesh.remove(slot.key);
                detachLane(slot);
                return;
            }
        }
    }

    private void completeStream(ViewStreamSlot<B> slot, ViewStreamMessage.BrickMiss.Plate miss, long now) {
        EncodedPlate encoded = slot.awaitingEncoded;
        int revision = slot.awaitingRevision;
        slot.awaitingEncoded = null;
        slot.missDeadlineNanos = 0L;
        ViewStreamMessage.PlateBricks bricks = miss == null
            ? encoded.bricksMessage(slot.key, revision)
            : encoded.bricksMessage(slot.key, revision, miss);
        try {
            int last = emit(List.of(bricks, encoded.end(slot.key, revision)), true);
            closeWindow(slot, last, now);
        } catch (ViewStreamProtocolException failure) {
            slot.failed = true;
            abandonWindow(slot);
            platform.warnings().accept("View stream bricks failed for endpoint " + slot.portalId + " and player " + playerId, failure);
        }
    }

    private void closeWindow(ViewStreamSlot<B> slot, int closeSequence, long now) {
        if (!slot.windowOpen) {
            return;
        }
        slot.windowOpen = false;
        ViewStreamAckWindow active = window;
        if (active != null) {
            active.close(slot.windowSequence, closeSequence, now);
        }
    }

    private void abandonWindow(ViewStreamSlot<B> slot) {
        if (!slot.windowOpen) {
            return;
        }
        slot.windowOpen = false;
        ViewStreamAckWindow active = window;
        if (active != null && active.abandon(slot.windowSequence) && paused) {
            paused = false;
            lane.submit();
        }
    }

    private void sendScene() {
        for (int i = 0; i < laneScene.size(); i++) {
            Scene<B> scene = laneScene.get(i);
            ViewStreamSlot<B> slot = scene.slot();
            if (!slot.laneAttached || !slot.announced || slot.standby) {
                resendScene(slot, scene.message());
                continue;
            }
            try {
                boolean bindSelf = entitySelfPending && scene.message() instanceof ViewStreamMessage.EntityFrame;
                List<ViewStreamMessage> group = bindSelf
                    ? List.of(new ViewStreamMessage.EntitySelf(platform.entities().projectedId(playerId)), scene.message())
                    : List.of(scene.message());
                emit(group, false);
                if (bindSelf) {
                    entitySelfPending = false;
                }
            } catch (ViewStreamProtocolException failure) {
                resendScene(slot, scene.message());
                platform.warnings().accept("View stream " + registry.codec().name(scene.message()) + " failed for player " + playerId, failure);
            }
        }
        laneScene.clear();
    }

    private void sendBursts() {
        if (laneBursts.isEmpty()) {
            return;
        }
        try {
            List<ViewStreamMessage.Extension> coalesced = registry.codec().coalesce(laneBursts);
            for (int i = 0; i < coalesced.size(); i++) {
                emit(List.of(coalesced.get(i)), false);
            }
        } catch (ViewStreamProtocolException failure) {
            platform.warnings().accept("View stream bursts failed for player " + playerId, failure);
        }
        laneBursts.clear();
    }

    private static void resendScene(ViewStreamSlot<?> slot, ViewStreamMessage message) {
        if (message instanceof ViewStreamMessage.EntityFrame) {
            slot.needFullEntities = true;
        } else {
            slot.needFullScene = true;
        }
    }

    private boolean meshAnnounced(int key) {
        for (ViewStreamSlot<B> slot : laneSlots) {
            if (slot.key == key) {
                return slot.announced && slot.sentGeometry == slot.geometry;
            }
        }
        return false;
    }

    private void sendMesh() {
        for (int sent = 0; sent < 256; sent++) {
            ViewStreamMessage control = mesh.pollControl(this::meshAnnounced);
            if (control == null) {
                break;
            }
            emitQuietly(List.of(control));
        }
        MeshStream.Ready<B> ready;
        while ((ready = mesh.poll(platform.nanoClock().getAsLong())) != null) {
            if (!mesh.current(ready)) {
                continue;
            }
            try {
                EncodedPlate encoded = registry.encoderFor(ready.light()).encode(ready.plate(), ready.light(), true);
                if (encoded.brickCount() != 1) {
                    throw new IllegalStateException("mesh section encoded " + encoded.brickCount() + " bricks");
                }
                if (mesh.unchanged(ready, encoded.hashes(registry.hashSalt())[0] ^ Integer.toUnsignedLong(ready.biomes().hashCode()), encoded.backingState())) {
                    continue;
                }
                MeshPlan.Coordinate coordinate = ready.coordinate();
                ViewStreamMessage.MeshSection section = new ViewStreamMessage.MeshSection(ready.slot().key, ready.generation(),
                    coordinate.x(), coordinate.y(), coordinate.z(), ready.revision(), encoded.backingState(), encoded.brick(0), ready.biomes());
                List<ViewStreamMessage> group = new ArrayList<ViewStreamMessage>(2);
                long reuseHash = ViewStreamCapability.MESH_REUSE.in(caps)
                    ? MeshHash.resolved(section, registry.hashSalt(), registry.palette()::state) : 0L;
                if (ViewStreamCapability.MESH_REUSE.in(caps) && mesh.reuse(ready, reuseHash)) {
                    group.add(new ViewStreamMessage.MeshReuse(section.portalKey(), section.generation(), section.sectionX(), section.sectionY(),
                        section.sectionZ(), section.revision(), reuseHash));
                } else {
                    List<ViewStreamMessage.PaletteEntry> entries = cursor.pending(encoded.referencedIds());
                    if (!entries.isEmpty()) {
                        group.add(new ViewStreamMessage.Palette(entries));
                    }
                    group.add(section);
                }
                int bytes = 0;
                for (ViewStreamMessage message : group) {
                    bytes += registry.codec().encodeBody(message).length + ViewStreamLimits.S2C_HEADER_BYTES;
                }
                if (bytes > MeshStream.SECTION_RESERVATION_BYTES) {
                    throw new ViewStreamProtocolException("mesh section plus palette needs " + bytes + " bytes; reservation is "
                        + MeshStream.SECTION_RESERVATION_BYTES);
                }
                if (mesh.current(ready)) {
                    emit(group, false);
                }
            } catch (ViewStreamProtocolException | IllegalArgumentException | IllegalStateException failure) {
                if (!mesh.current(ready)) {
                    continue;
                }
                ready.slot().failed = true;
                mesh.remove(ready.slot().key);
                detachLane(ready.slot());
                cursor.reset();
                platform.warnings().accept("View stream mesh transfer failed for player " + playerId + ", endpoint " + ready.slot().portalId
                    + ", generation " + ready.generation() + ", section " + ready.coordinate() + ", frame limit " + splitter.maxFrameBytes(), failure);
            }
        }
    }

    private void emitQuietly(List<ViewStreamMessage> group) {
        try {
            emit(group, false);
        } catch (ViewStreamProtocolException failure) {
            platform.warnings().accept("View stream control frame failed for player " + playerId, failure);
        }
    }

    private int emit(List<ViewStreamMessage> group, boolean close) throws ViewStreamProtocolException {
        List<byte[]> frames = splitter.split(group, laneSequence, close);
        long bytes = 0L;
        for (int i = 0; i < frames.size(); i++) {
            byte[] frame = frames.get(i);
            platform.transport().send(player, frame);
            bytes += frame.length;
        }
        framesSent.addAndGet(frames.size());
        bytesSent.addAndGet(bytes);
        laneSent = true;
        if (close) {
            groupsSent.incrementAndGet();
        }
        return lastLaneSequence;
    }

    private void sendDirect(ViewStreamMessage message) {
        try {
            byte[] frame = registry.codec().encodeS2C(message, sequence.getAndIncrement(), ViewStreamLimits.FLAG_LAST);
            platform.transport().send(player, frame);
            platform.transport().flush(player);
            framesSent.incrementAndGet();
            bytesSent.addAndGet(frame.length);
        } catch (ViewStreamProtocolException failure) {
            platform.warnings().accept("View stream " + registry.codec().name(message) + " failed for player " + playerId, failure);
        }
    }

    private int nextLaneSequence() {
        int next = sequence.getAndIncrement();
        lastLaneSequence = next;
        return next;
    }

    private long millis() {
        return platform.nanoClock().getAsLong() / NANOS_PER_MILLI;
    }

    private static int[] geometryPaletteIds(ApertureDescriptor geometry) {
        if (geometry.nested().isEmpty()) {
            return new int[] {geometry.blackoutState()};
        }
        ArrayList<ApertureDescriptor> pending = new ArrayList<ApertureDescriptor>();
        pending.add(geometry);
        int[] ids = new int[4];
        int count = 0;
        while (!pending.isEmpty()) {
            ApertureDescriptor next = pending.remove(pending.size() - 1);
            if (count == ids.length) {
                ids = Arrays.copyOf(ids, count * 2);
            }
            ids[count++] = next.blackoutState();
            pending.addAll(next.nested());
        }
        return Arrays.copyOf(ids, count);
    }

    private static boolean terminal(ViewStreamMessage.ResetReason reason) {
        return reason == ViewStreamMessage.ResetReason.DISABLED || reason == ViewStreamMessage.ResetReason.PROTOCOL
            || reason == ViewStreamMessage.ResetReason.OVERLOAD;
    }

    private enum Refresh {
        UNCHANGED,
        CHANGED,
        DETACH,
        REJECT
    }

    private sealed interface Command<B> permits Open, Attach, Detach, Miss, Refused, Scene, Burst, Reset {
    }

    private record Open<B>(ViewStreamMessage.Accept accept) implements Command<B> {
    }

    private record Attach<B>(ViewStreamSlot<B> slot) implements Command<B> {
    }

    private record Detach<B>(ViewStreamSlot<B> slot) implements Command<B> {
    }

    private record Miss<B>(ViewStreamMessage.BrickMiss.Plate miss) implements Command<B> {
    }

    private record Refused<B>(int portalKey, int revision) implements Command<B> {
    }

    private record Scene<B>(ViewStreamSlot<B> slot, ViewStreamMessage message) implements Command<B> {
    }

    private record Burst<B>(ViewStreamMessage.Extension message) implements Command<B> {
    }

    private record Reset<B>(ViewStreamMessage.ResetReason reason, boolean terminal) implements Command<B> {
    }

    public interface Hooks<O> {
        boolean onExtension(O peer, Object payload);

        void tickExtension(O peer, long nowMillis, Predicate<ViewStreamMessage> sender);

        void onReset(O peer);

        void onClose(O peer);

        static <O> Hooks<O> none() {
            return new Hooks<O>() {
                @Override
                public boolean onExtension(O peer, Object payload) {
                    return false;
                }

                @Override
                public void tickExtension(O peer, long nowMillis, Predicate<ViewStreamMessage> sender) {
                }

                @Override
                public void onReset(O peer) {
                }

                @Override
                public void onClose(O peer) {
                }
            };
        }
    }

    @FunctionalInterface
    public interface HooksFactory<O, B> {
        Hooks<O> create(ViewStreamSession<O, B> session);
    }
}
