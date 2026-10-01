package art.arcane.wormholes.render.client.session;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewHandshake;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.network.client.ClientViewRateLimiter;
import art.arcane.wormholes.network.client.EncodedPlate;
import art.arcane.wormholes.network.client.FrameSplitter;
import art.arcane.wormholes.network.client.PlatePatchEncoder;
import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.plate.ViewPlate;

public final class ClientViewServerSession<P, B> {
    static final long BRICK_MISS_TIMEOUT_NANOS = 5_000_000_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int PLAY_PHASE_GRACE_MILLIS = ClientViewProtocol.PLAY_PHASE_PENDING_TICKS * 50;
    private static final int NESTED_GEOMETRY = 1;
    private static final int NESTED_PLATE = 1 << 1;
    private static final int MAX_PENDING_BURSTS = 1024;

    private final ClientViewSessionRegistry<P, B> registry;
    private final ClientViewPlatform<P, B> platform;
    private final UUID playerId;
    private final P player;
    private final long zeroCopyNonce;
    private final ClientViewLane lane;
    private final ConcurrentLinkedQueue<Command<B>> inbox;
    private final Object handshakeLock;
    private final ClientViewRateLimiter limiter;
    private final AtomicInteger sequence;
    private final IntSupplier laneSequence;
    private final HashMap<UUID, ClientViewPortalSlot<B>> slots;
    private final ArrayList<ClientViewPortalSlot<B>> slotList;
    private final ArrayList<UUID> interest;
    private final ArrayList<UUID> effectInterest;
    private final HashSet<UUID> rejected;
    private final ArrayList<UUID> nestedScratch;
    private final ArrayList<ClientViewPortalSlot<B>> laneSlots;
    private final ArrayList<Scene<B>> laneScene;
    private final ArrayList<ClientViewMessage.FxEmitter> laneBursts;
    private final AtomicInteger pendingBursts;
    private final AtomicLong framesSent;
    private final AtomicLong bytesSent;
    private final AtomicLong groupsSent;
    private final AtomicLong c2sDropped;
    private final AtomicLong c2sStale;
    private final AtomicLong staleMisses;
    private final AtomicLong lateSwitches;
    private volatile ClientViewSessionState state;
    private volatile ClientViewAckWindow window;
    private volatile long caps;
    private volatile int sessionId;
    private volatile ClientViewMessage.ViewStats viewStats;
    private volatile long viewStatsMillis;
    private volatile int attended;
    private volatile boolean paused;
    private volatile boolean closed;
    private ClientViewHandshake handshake;
    private String brandTag;
    private int nextPortalKey;
    private SessionPalette.Cursor cursor;
    private FrameSplitter splitter;
    private long laneCaps;
    private boolean laneOpen;
    private boolean laneSent;
    private int lastLaneSequence;

    ClientViewServerSession(ClientViewSessionRegistry<P, B> registry, UUID playerId, P player, long zeroCopyNonce) {
        this.registry = registry;
        this.platform = registry.platform();
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.player = Objects.requireNonNull(player, "player");
        this.zeroCopyNonce = zeroCopyNonce;
        this.lane = new ClientViewLane(platform.lanes(), this::drainSafely);
        this.inbox = new ConcurrentLinkedQueue<Command<B>>();
        this.handshakeLock = new Object();
        this.limiter = new ClientViewRateLimiter();
        this.sequence = new AtomicInteger();
        this.laneSequence = this::nextLaneSequence;
        this.slots = new HashMap<UUID, ClientViewPortalSlot<B>>();
        this.slotList = new ArrayList<ClientViewPortalSlot<B>>();
        this.interest = new ArrayList<UUID>();
        this.effectInterest = new ArrayList<UUID>();
        this.rejected = new HashSet<UUID>();
        this.nestedScratch = new ArrayList<UUID>(4);
        this.laneSlots = new ArrayList<ClientViewPortalSlot<B>>();
        this.laneScene = new ArrayList<Scene<B>>();
        this.laneBursts = new ArrayList<ClientViewMessage.FxEmitter>();
        this.pendingBursts = new AtomicInteger();
        this.framesSent = new AtomicLong();
        this.bytesSent = new AtomicLong();
        this.groupsSent = new AtomicLong();
        this.c2sDropped = new AtomicLong();
        this.c2sStale = new AtomicLong();
        this.staleMisses = new AtomicLong();
        this.lateSwitches = new AtomicLong();
        this.state = ClientViewSessionState.VANILLA;
        this.nextPortalKey = 1;
    }

    public UUID playerId() {
        return playerId;
    }

    public P player() {
        return player;
    }

    public ClientViewSessionState state() {
        return state;
    }

    public boolean holdsVanilla() {
        return state == ClientViewSessionState.PENDING;
    }

    public long caps() {
        return caps;
    }

    public boolean owns(UUID portal) {
        ClientViewPortalSlot<B> slot = state == ClientViewSessionState.CLIENT_VIEW ? slots.get(portal) : null;
        return slot != null && !slot.effects;
    }

    public boolean effectsReceiver() {
        return !closed && state == ClientViewSessionState.CLIENT_VIEW && ClientViewCapability.FX_EMITTERS.in(caps);
    }

    public boolean oneShot(ClientViewMessage.FxEmitter emitter) {
        Objects.requireNonNull(emitter, "emitter");
        if (!effectsReceiver()) {
            return false;
        }
        if (pendingBursts.incrementAndGet() > MAX_PENDING_BURSTS) {
            pendingBursts.decrementAndGet();
            return false;
        }
        inbox.add(new Burst<B>(emitter));
        lane.submit();
        return true;
    }

    public boolean offer(ClientViewPhase phase) {
        Objects.requireNonNull(phase, "phase");
        ClientViewMessage.Offer offer;
        synchronized (handshakeLock) {
            if (closed || state != ClientViewSessionState.VANILLA || !registry.enabled()) {
                return false;
            }
            ClientViewHandshake.Policy policy = policy(phase);
            handshake = new ClientViewHandshake(policy, zeroCopyNonce, registry::nextSessionId, registry::hashSalt);
            long now = millis();
            if (brandTag != null) {
                handshake.brand(brandTag, now);
            }
            offer = handshake.offer(now);
            state = ClientViewSessionState.PENDING;
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
            if (handshake == null || state != ClientViewSessionState.PENDING) {
                return;
            }
            if (handshake.onPong(millis()).state() == ClientViewHandshake.State.VANILLA) {
                state = ClientViewSessionState.VANILLA;
            }
            handshakeLock.notifyAll();
        }
    }

    public boolean awaitingHello() {
        synchronized (handshakeLock) {
            return state == ClientViewSessionState.PENDING && handshake != null && handshake.waiting(millis());
        }
    }

    public ClientViewSessionState expire() {
        synchronized (handshakeLock) {
            if (state == ClientViewSessionState.PENDING && handshake != null
                && handshake.onDeadline(millis()).state() != ClientViewHandshake.State.OFFERED) {
                state = ClientViewSessionState.VANILLA;
                handshakeLock.notifyAll();
            }
            return state;
        }
    }

    public ClientViewSessionState awaitHandshake() throws InterruptedException {
        synchronized (handshakeLock) {
            while (!closed && state == ClientViewSessionState.PENDING && handshake != null && handshake.waiting(millis())) {
                long remaining = handshake.deadlineMillis() - millis();
                handshakeLock.wait(Math.max(1L, remaining));
            }
        }
        return expire();
    }

    public void tick(long serverTick) {
        ClientViewSessionState current = state;
        if (current == ClientViewSessionState.PENDING) {
            expire();
            return;
        }
        if (current != ClientViewSessionState.CLIENT_VIEW || closed) {
            if (!slotList.isEmpty() || !rejected.isEmpty()) {
                forgetOwned();
            }
            return;
        }
        ClientViewOptions options = registry.options();
        ClientViewPortalAccess<P, B> portals = platform.portals();
        interest.clear();
        if (ClientViewCapability.PLATES.in(caps)) {
            portals.interested(player, interest);
        }
        boolean plateless = false;
        for (int i = 0; i < interest.size(); i++) {
            UUID portal = interest.get(i);
            ClientViewPortalSlot<B> slot = slots.get(portal);
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
        if (ClientViewCapability.FX_EMITTERS.in(caps)) {
            attachEffects(portals, serverTick);
        }
        boolean signal = plateless || lane.stalled();
        long now = platform.nanoClock().getAsLong();
        int grace = options.interestGraceTicks();
        int owned = 0;
        for (int i = slotList.size() - 1; i >= 0; i--) {
            ClientViewPortalSlot<B> slot = slotList.get(i);
            long seen = slot.effects ? slot.lastEffectTick : slot.lastInterestTick;
            if (slot.failed || serverTick - seen > grace) {
                if (slot.failed && !slot.effects) {
                    rejected.add(slot.portalId);
                }
                detach(i, slot);
                signal = true;
                continue;
            }
            Refresh outcome = refresh(slot, portals, options, serverTick);
            if (outcome == Refresh.REJECT) {
                rejected.add(slot.portalId);
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
    }

    public void reset(ClientViewMessage.ResetReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (terminal(reason)) {
            end(reason);
            return;
        }
        if (state != ClientViewSessionState.CLIENT_VIEW || closed) {
            return;
        }
        inbox.add(new Reset<B>(reason, false));
        if (reason == ClientViewMessage.ResetReason.TELEPORT) {
            restream();
        } else {
            forgetOwned();
        }
        lane.submit();
    }

    public void end(ClientViewMessage.ResetReason reason) {
        Objects.requireNonNull(reason, "reason");
        synchronized (handshakeLock) {
            ClientViewSessionState previous = state;
            if (previous == ClientViewSessionState.VANILLA) {
                return;
            }
            state = ClientViewSessionState.VANILLA;
            handshakeLock.notifyAll();
            if (previous != ClientViewSessionState.CLIENT_VIEW) {
                return;
            }
        }
        inbox.add(new Reset<B>(reason, true));
        lane.submit();
    }

    public ClientViewInbound receive(byte[] payload, int offset, int length) {
        if (closed) {
            return ClientViewInbound.IGNORED;
        }
        long now = millis();
        ClientViewRateLimiter.Verdict verdict = limiter.admit(now, length);
        if (verdict != ClientViewRateLimiter.Verdict.ACCEPT) {
            return rejectInbound(verdict);
        }
        ClientViewMessage message;
        try {
            message = ClientViewCodec.decodeC2S(payload, offset, length);
        } catch (ClientViewProtocolException malformed) {
            return rejectInbound(limiter.violation(now));
        }
        return switch (message) {
            case ClientViewMessage.Hello hello -> onHello(hello, now);
            case ClientViewMessage.BrickMiss misses -> onMiss(misses);
            case ClientViewMessage.Ack ack -> onAck(ack);
            case ClientViewMessage.ViewStats stats -> onViewStats(stats, now);
            case ClientViewMessage.PlateRefused refused -> onRefused(refused);
            default -> rejectInbound(limiter.violation(now));
        };
    }

    public ClientViewSessionStats stats() {
        ClientViewAckWindow active = window;
        return new ClientViewSessionStats(playerId, sessionId, state, caps, attended, framesSent.get(), bytesSent.get(),
            groupsSent.get(), active == null ? 0 : active.outstanding(), active == null ? 0L : active.acked(),
            active == null ? 0L : active.lastRttNanos() / 1000L, active == null ? 0L : active.appliedCells(), limiter.admitted(),
            c2sDropped.get(), c2sStale.get(), staleMisses.get(), lateSwitches.get(), viewStats);
    }

    void close() {
        synchronized (handshakeLock) {
            closed = true;
            state = ClientViewSessionState.VANILLA;
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
                case Refused<B> refused -> refuseLane(refused.portalKey());
                case Scene<B> scene -> laneScene.add(scene);
                case Burst<B> burst -> {
                    pendingBursts.decrementAndGet();
                    laneBursts.add(burst.emitter());
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
            platform.warnings().accept("ClientView lane failed for " + playerId, failure);
        }
    }

    private ClientViewHandshake.Policy policy(ClientViewPhase phase) {
        ClientViewOptions options = registry.options();
        int grace = phase == ClientViewPhase.PLAY ? PLAY_PHASE_GRACE_MILLIS : options.helloGraceMillis();
        long serverCaps = options.serverCaps(phase) & platform.platformCaps();
        return new ClientViewHandshake.Policy(registry.enabled(), platform.mcDataVersion(), serverCaps, options.maxFrameBytes(), grace,
            ClientViewProtocol.DEFAULT_TICK_RATE, options.ackWindowFrames(), options.zeroCopy());
    }

    private ClientViewInbound onHello(ClientViewMessage.Hello hello, long now) {
        ClientViewHandshake.Result result;
        synchronized (handshakeLock) {
            if (handshake == null || state == ClientViewSessionState.CLIENT_VIEW) {
                return stale();
            }
            result = handshake.onHello(hello, now, true);
            if (result.reply() == null) {
                return stale();
            }
            sendDirect(result.reply());
            if (!result.accepted()) {
                state = ClientViewSessionState.VANILLA;
                handshakeLock.notifyAll();
                return ClientViewInbound.HELLO_DECLINED;
            }
            ClientViewMessage.Accept accept = handshake.accepted();
            caps = accept.caps();
            sessionId = accept.sessionId();
            window = new ClientViewAckWindow(accept.ackWindowFrames());
            inbox.add(new Open<B>(accept));
            if (result.late()) {
                lateSwitches.incrementAndGet();
            }
            state = ClientViewSessionState.CLIENT_VIEW;
            handshakeLock.notifyAll();
        }
        lane.submit();
        return ClientViewInbound.HELLO_ACCEPTED;
    }

    private ClientViewInbound onMiss(ClientViewMessage.BrickMiss misses) {
        if (state != ClientViewSessionState.CLIENT_VIEW || !ClientViewCapability.BRICK_CACHE.in(caps)) {
            return stale();
        }
        List<ClientViewMessage.BrickMiss.Plate> plates = misses.plates();
        for (int i = 0; i < plates.size(); i++) {
            inbox.add(new Miss<B>(plates.get(i)));
        }
        lane.submit();
        return ClientViewInbound.HANDLED;
    }

    private ClientViewInbound onRefused(ClientViewMessage.PlateRefused refused) {
        if (state != ClientViewSessionState.CLIENT_VIEW) {
            return stale();
        }
        inbox.add(new Refused<B>(refused.portalKey()));
        lane.submit();
        return ClientViewInbound.HANDLED;
    }

    private ClientViewInbound onAck(ClientViewMessage.Ack ack) {
        ClientViewAckWindow active = window;
        if (state != ClientViewSessionState.CLIENT_VIEW || active == null) {
            return stale();
        }
        if (active.ack(ack.seq(), ack.appliedCells(), platform.nanoClock().getAsLong()) && paused) {
            paused = false;
            lane.submit();
        }
        return ClientViewInbound.HANDLED;
    }

    private ClientViewInbound onViewStats(ClientViewMessage.ViewStats stats, long now) {
        if (state != ClientViewSessionState.CLIENT_VIEW || !ClientViewCapability.VIEW_STATS.in(caps) || !registry.options().viewStats()
            || (viewStats != null && now - viewStatsMillis < ClientViewProtocol.VIEW_STATS_MIN_INTERVAL_MILLIS - ClientViewProtocol.VIEW_STATS_JITTER_MILLIS)) {
            return stale();
        }
        viewStats = stats;
        viewStatsMillis = now;
        return ClientViewInbound.HANDLED;
    }

    private ClientViewInbound stale() {
        c2sStale.incrementAndGet();
        return ClientViewInbound.IGNORED;
    }

    private ClientViewInbound rejectInbound(ClientViewRateLimiter.Verdict verdict) {
        c2sDropped.incrementAndGet();
        if (verdict == ClientViewRateLimiter.Verdict.RESET) {
            end(ClientViewMessage.ResetReason.PROTOCOL);
            return ClientViewInbound.RESET;
        }
        return ClientViewInbound.DROPPED;
    }

    private ClientViewPortalSlot<B> attach(UUID portal, ClientViewPortalAccess<P, B> portals) {
        if (rejected.contains(portal)) {
            return null;
        }
        long stamp = portals.geometryRevision(player, portal);
        ClientPortalGeometry geometry = ownable(portal, portals);
        if (geometry == null) {
            return null;
        }
        ClientViewPortalSlot<B> slot = new ClientViewPortalSlot<B>(portal, nextPortalKey++, false);
        own(slot, stamp, geometry, portals);
        slots.put(portal, slot);
        slotList.add(slot);
        inbox.add(new Attach<B>(slot));
        return slot;
    }

    private boolean promote(ClientViewPortalSlot<B> slot, ClientViewPortalAccess<P, B> portals) {
        if (rejected.contains(slot.portalId)) {
            return false;
        }
        long stamp = portals.geometryRevision(player, slot.portalId);
        ClientPortalGeometry geometry = ownable(slot.portalId, portals);
        if (geometry == null) {
            return false;
        }
        own(slot, stamp, geometry, portals);
        return true;
    }

    private ClientPortalGeometry ownable(UUID portal, ClientViewPortalAccess<P, B> portals) {
        ClientPortalGeometry geometry = portals.geometry(player, portal, registry.palette());
        if (geometry == null) {
            rejected.add(portal);
            return null;
        }
        return clientMirror(geometry) || !portals.refused(player, portal) ? geometry : null;
    }

    private void own(ClientViewPortalSlot<B> slot, long stamp, ClientPortalGeometry geometry, ClientViewPortalAccess<P, B> portals) {
        slot.effects = false;
        slot.geometryStamp = stamp;
        slot.baseGeometry = geometry;
        slot.geometry = geometry;
        slot.needFullEntities = true;
        slot.needFullScene = true;
        portals.releaseVanilla(player, slot.portalId);
    }

    private void attachEffects(ClientViewPortalAccess<P, B> portals, long serverTick) {
        effectInterest.clear();
        portals.effects(player, effectInterest);
        for (int i = 0; i < effectInterest.size(); i++) {
            UUID portal = effectInterest.get(i);
            ClientViewPortalSlot<B> slot = slots.get(portal);
            if (slot == null) {
                long stamp = portals.effectGeometryRevision(player, portal);
                ClientPortalGeometry geometry = portals.effectGeometry(player, portal, registry.palette());
                if (geometry == null) {
                    continue;
                }
                slot = new ClientViewPortalSlot<B>(portal, nextPortalKey++, false);
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

    private void detach(int index, ClientViewPortalSlot<B> slot) {
        slotList.remove(index);
        slots.remove(slot.portalId);
        inbox.add(new Detach<B>(slot));
        detachChildren(slot);
        if (slot.standbySlot != null) {
            inbox.add(new Detach<B>(slot.standbySlot));
            slot.standbySlot = null;
        }
    }

    private void detachChildren(ClientViewPortalSlot<B> slot) {
        for (int i = 0; i < slot.children.size(); i++) {
            inbox.add(new Detach<B>(slot.children.get(i)));
        }
        slot.children.clear();
    }

    private void restream() {
        for (int i = 0; i < slotList.size(); i++) {
            ClientViewPortalSlot<B> successor = slotList.get(i).successor(nextPortalKey++);
            slotList.set(i, successor);
            slots.put(successor.portalId, successor);
            inbox.add(new Attach<B>(successor));
        }
    }

    private void forgetOwned() {
        slots.clear();
        slotList.clear();
        rejected.clear();
        attended = 0;
    }

    private Refresh refresh(ClientViewPortalSlot<B> slot, ClientViewPortalAccess<P, B> portals, ClientViewOptions options, long serverTick) {
        if (slot.effects) {
            return refreshEffects(slot, portals, options, serverTick);
        }
        boolean changed = false;
        boolean geometryChanged = false;
        long stamp = portals.geometryRevision(player, slot.portalId);
        if (stamp != slot.geometryStamp) {
            ClientPortalGeometry geometry = portals.geometry(player, slot.portalId, registry.palette());
            if (geometry == null) {
                return Refresh.REJECT;
            }
            slot.geometryStamp = stamp;
            slot.baseGeometry = geometry;
            geometryChanged = true;
        }
        if (!clientMirror(slot.baseGeometry)) {
            ViewPlate<B> plate = portals.plate(player, slot.portalId, slot.observedPlate == null);
            if (plate == null) {
                if (portals.refused(player, slot.portalId)) {
                    return Refresh.DETACH;
                }
            } else if (plate != slot.observedPlate) {
                slot.observedPlate = plate;
                slot.target = new ClientViewPortalSlot.PlateTarget<B>(plate, light(slot.portalId, plate, portals, options));
                changed = true;
            }
        }
        int nested = refreshNested(slot, portals, options);
        if (geometryChanged || (nested & NESTED_GEOMETRY) != 0) {
            slot.geometry = compose(slot);
        }
        changed |= geometryChanged || nested != 0;
        changed |= refreshStandby(slot, portals, options);
        changed |= refreshScene(slot, options, serverTick);
        return changed ? Refresh.CHANGED : Refresh.UNCHANGED;
    }

    private Refresh refreshEffects(ClientViewPortalSlot<B> slot, ClientViewPortalAccess<P, B> portals, ClientViewOptions options,
                                   long serverTick) {
        boolean changed = false;
        long stamp = portals.effectGeometryRevision(player, slot.portalId);
        if (stamp != slot.geometryStamp) {
            ClientPortalGeometry geometry = portals.effectGeometry(player, slot.portalId, registry.palette());
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

    private boolean refreshStandby(ClientViewPortalSlot<B> slot, ClientViewPortalAccess<P, B> portals, ClientViewOptions options) {
        ClientViewPortalSlot<B> shadow = slot.standbySlot;
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
            shadow = new ClientViewPortalSlot<B>(slot.portalId, nextPortalKey++, true);
            slot.standbySlot = shadow;
            inbox.add(new Attach<B>(shadow));
        }
        shadow.observedPlate = standby;
        shadow.target = new ClientViewPortalSlot.PlateTarget<B>(standby, light(slot.portalId, standby, portals, options));
        return true;
    }

    private boolean refreshScene(ClientViewPortalSlot<B> slot, ClientViewOptions options, long serverTick) {
        long sessionCaps = caps;
        boolean queued = false;
        if (!slot.effects && options.entityFrames() && ClientViewCapability.ENTITY_FRAMES.in(sessionCaps)) {
            ClientViewMessage.EntityFrame frame = platform.entities().frame(player, slot.portalId, slot.key, serverTick, slot.needFullEntities,
                clientMirror(slot.baseGeometry));
            if (frame != null) {
                slot.needFullEntities = false;
                inbox.add(new Scene<B>(slot, frame));
                queued = true;
            }
        }
        boolean fullScene = slot.needFullScene;
        slot.needFullScene = false;
        if (ClientViewCapability.FX_EMITTERS.in(sessionCaps)) {
            ClientViewMessage.Fx fx = platform.fx().fx(player, slot.portalId, slot.key, serverTick, fullScene);
            if (fx != null) {
                inbox.add(new Scene<B>(slot, fx));
                queued = true;
            }
        }
        if (!slot.effects && ClientViewCapability.ATMOSPHERE.in(sessionCaps)) {
            ClientViewMessage.Atmosphere atmosphere = platform.fx().atmosphere(player, slot.portalId, slot.key, serverTick, fullScene);
            if (atmosphere != null) {
                inbox.add(new Scene<B>(slot, atmosphere));
                queued = true;
            }
        }
        return queued;
    }

    private int refreshNested(ClientViewPortalSlot<B> slot, ClientViewPortalAccess<P, B> portals, ClientViewOptions options) {
        if (!clientRecursion(slot.baseGeometry)) {
            if (slot.children.isEmpty()) {
                return 0;
            }
            detachChildren(slot);
            return NESTED_GEOMETRY;
        }
        nestedScratch.clear();
        portals.nested(player, slot.portalId, slot.baseGeometry, nestedScratch);
        int limit = Math.min(nestedScratch.size(), ClientViewProtocol.MAX_NESTED_GEOMETRY);
        int flags = 0;
        for (int i = slot.children.size() - 1; i >= 0; i--) {
            ClientViewPortalSlot<B> child = slot.children.get(i);
            int index = nestedScratch.indexOf(child.childId);
            if (child.failed || index < 0 || index >= limit) {
                slot.children.remove(i);
                inbox.add(new Detach<B>(child));
                flags |= NESTED_GEOMETRY;
            }
        }
        for (int i = 0; i < limit; i++) {
            UUID childId = nestedScratch.get(i);
            ClientViewPortalSlot<B> child = slot.child(childId);
            long stamp = portals.nestedGeometryRevision(player, slot.portalId, childId);
            if (child == null || stamp != child.geometryStamp) {
                ClientPortalGeometry geometry = portals.nestedGeometry(player, slot.portalId, childId, registry.palette());
                if (geometry == null || !geometry.nested().isEmpty()) {
                    if (child != null) {
                        slot.children.remove(child);
                        inbox.add(new Detach<B>(child));
                        flags |= NESTED_GEOMETRY;
                    }
                    continue;
                }
                if (child == null) {
                    child = new ClientViewPortalSlot<B>(slot.portalId, nextPortalKey++, false, childId);
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
                child.target = new ClientViewPortalSlot.PlateTarget<B>(plate, light(childId, plate, portals, options));
                flags |= NESTED_PLATE;
            }
        }
        return flags;
    }

    private static <B> ClientPortalGeometry compose(ClientViewPortalSlot<B> slot) {
        if (slot.children.isEmpty()) {
            return slot.baseGeometry;
        }
        List<ClientPortalGeometry> nested = new ArrayList<ClientPortalGeometry>(slot.children.size());
        for (int i = 0; i < slot.children.size(); i++) {
            nested.add(slot.children.get(i).geometry);
        }
        return slot.baseGeometry.withNested(nested);
    }

    private boolean clientMirror(ClientPortalGeometry geometry) {
        return geometry != null && geometry.mirror() && ClientViewCapability.CLIENT_MIRROR.in(caps) && registry.options().clientMirror();
    }

    private boolean clientRecursion(ClientPortalGeometry geometry) {
        return geometry != null && geometry.recursionDepth() > 0 && ClientViewCapability.CLIENT_RECURSION.in(caps)
            && registry.options().clientRecursion();
    }

    private BrickLightSource light(UUID portal, ViewPlate<B> plate, ClientViewPortalAccess<P, B> portals, ClientViewOptions options) {
        if (!options.destinationLight() || !ClientViewCapability.DEST_LIGHT.in(caps)) {
            return BrickLightSource.NONE;
        }
        BrickLightSource light = portals.lightBaseline(player, portal, plate);
        return light == null ? BrickLightSource.NONE : light;
    }

    private static boolean missDue(ClientViewPortalSlot<?> slot, long now) {
        long deadline = slot.missDeadlineNanos;
        return deadline != 0L && now - deadline >= 0L;
    }

    private void openLane(Open<B> open) {
        laneSlots.clear();
        laneScene.clear();
        cursor = registry.palette().cursor();
        laneCaps = open.accept().caps();
        splitter = new FrameSplitter(open.accept().maxFrameBytes(), ClientViewCapability.LINK_UNCOMPRESSED.in(laneCaps));
        laneOpen = true;
    }

    private void attachLane(ClientViewPortalSlot<B> slot) {
        if (!laneOpen) {
            return;
        }
        slot.laneAttached = true;
        laneSlots.add(slot);
    }

    private void detachLane(ClientViewPortalSlot<B> slot) {
        if (!slot.laneAttached) {
            return;
        }
        slot.laneAttached = false;
        slot.awaitingEncoded = null;
        slot.missDeadlineNanos = 0L;
        abandonWindow(slot);
        laneSlots.remove(slot);
        if (slot.announced && laneOpen) {
            emitQuietly(List.of(new ClientViewMessage.PortalDrop(slot.key)));
        }
    }

    private void resetLane(ClientViewMessage.ResetReason reason, boolean terminal) {
        if (!laneOpen) {
            return;
        }
        for (int i = 0; i < laneSlots.size(); i++) {
            ClientViewPortalSlot<B> slot = laneSlots.get(i);
            slot.laneAttached = false;
            slot.awaitingEncoded = null;
            slot.missDeadlineNanos = 0L;
            slot.windowOpen = false;
        }
        laneSlots.clear();
        laneScene.clear();
        laneBursts.clear();
        cursor.reset();
        ClientViewAckWindow active = window;
        if (active != null) {
            active.clear();
        }
        paused = false;
        emitQuietly(List.of(new ClientViewMessage.SessionReset(reason)));
        if (terminal) {
            laneOpen = false;
        }
    }

    private boolean reconcile(ClientViewPortalSlot<B> slot, long now) {
        if (slot.awaitingMiss()) {
            if (now - slot.missDeadlineNanos < 0L) {
                return true;
            }
            completeStream(slot, null, now);
        }
        ClientPortalGeometry geometry = slot.geometry;
        ClientViewPortalSlot.PlateTarget<B> target = slot.target;
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
        } catch (ClientViewProtocolException | IllegalArgumentException | IllegalStateException failure) {
            slot.failed = true;
            cursor.reset();
            platform.warnings().accept("ClientView stream failed for portal " + slot.portalId + " and player " + playerId, failure);
        }
        return true;
    }

    private boolean windowFull() {
        ClientViewAckWindow active = window;
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

    private void stream(ClientViewPortalSlot<B> slot, ClientPortalGeometry geometry, ClientViewPortalSlot.PlateTarget<B> target, long now)
        throws ClientViewProtocolException {
        List<ClientViewMessage> group = new ArrayList<ClientViewMessage>(6);
        List<ClientViewMessage.PaletteEntry> entries = new ArrayList<ClientViewMessage.PaletteEntry>();
        int geometryRevision = slot.geometryRevision;
        if (geometry != null) {
            entries.addAll(cursor.pending(geometryPaletteIds(geometry)));
            geometryRevision++;
            group.add(new ClientViewMessage.Portal(slot.key, geometryRevision, geometry));
        }
        boolean close = true;
        EncodedPlate encoded = null;
        int plateRevision = slot.plateRevision;
        if (target != null) {
            plateRevision++;
            long handle = ClientViewCapability.ZERO_COPY.in(laneCaps)
                ? platform.handoffs().publish(slot.key, plateRevision, target.plate(), target.light())
                : 0L;
            if (handle != 0L) {
                group.add(new ClientViewMessage.PlateHandle(slot.key, plateRevision, handle));
            } else {
                encoded = registry.encoderFor(target.light()).encode(target.plate(), target.light(), true);
                entries.addAll(cursor.pending(encoded.referencedIds()));
                EncodedPlate previous = slot.sentEncoded;
                if (previous != null && slot.sentPlate.key().equals(target.plate().key()) && previous.sections().equals(encoded.sections())
                    && previous.cells().equals(encoded.cells())) {
                    ClientViewMessage.PlatePatch patch = PlatePatchEncoder.diff(slot.key, slot.plateRevision, plateRevision, previous, encoded);
                    if (patch.ops().isEmpty()) {
                        plateRevision = slot.plateRevision;
                    } else {
                        group.add(patch);
                    }
                } else if (ClientViewCapability.BRICK_CACHE.in(laneCaps) && hashManifestFits(slot.key, encoded)) {
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
            group.add(0, new ClientViewMessage.Palette(entries));
        }
        if (!group.isEmpty()) {
            int last = emit(group, close);
            ClientViewAckWindow active = window;
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

    private boolean hashManifestFits(int portalKey, EncodedPlate encoded) throws ClientViewProtocolException {
        int headerBytes = ClientViewCodec.encodeBody(encoded.begin(portalKey, 0, false, 0L)).length;
        long frameBytes = ClientViewProtocol.S2C_HEADER_BYTES + headerBytes + (long) Long.BYTES * encoded.brickCount();
        return frameBytes <= splitter.maxFrameBytes();
    }

    private void answerMiss(ClientViewMessage.BrickMiss.Plate miss, long now) {
        for (int i = 0; i < laneSlots.size(); i++) {
            ClientViewPortalSlot<B> slot = laneSlots.get(i);
            if (slot.key == miss.portalKey() && slot.awaitingMiss() && slot.awaitingRevision == miss.plateRevision()) {
                completeStream(slot, miss, now);
                return;
            }
        }
        staleMisses.incrementAndGet();
    }

    private void refuseLane(int portalKey) {
        for (int i = 0; i < laneSlots.size(); i++) {
            ClientViewPortalSlot<B> slot = laneSlots.get(i);
            if (slot.key == portalKey) {
                slot.awaitingEncoded = null;
                slot.missDeadlineNanos = 0L;
                slot.failed = true;
                return;
            }
        }
    }

    private void completeStream(ClientViewPortalSlot<B> slot, ClientViewMessage.BrickMiss.Plate miss, long now) {
        EncodedPlate encoded = slot.awaitingEncoded;
        int revision = slot.awaitingRevision;
        slot.awaitingEncoded = null;
        slot.missDeadlineNanos = 0L;
        ClientViewMessage.PlateBricks bricks = miss == null
            ? encoded.bricksMessage(slot.key, revision)
            : encoded.bricksMessage(slot.key, revision, miss);
        try {
            int last = emit(List.of(bricks, encoded.end(slot.key, revision)), true);
            closeWindow(slot, last, now);
        } catch (ClientViewProtocolException failure) {
            slot.failed = true;
            abandonWindow(slot);
            platform.warnings().accept("ClientView brick stream failed for portal " + slot.portalId + " and player " + playerId, failure);
        }
    }

    private void closeWindow(ClientViewPortalSlot<B> slot, int closeSequence, long now) {
        if (!slot.windowOpen) {
            return;
        }
        slot.windowOpen = false;
        ClientViewAckWindow active = window;
        if (active != null) {
            active.close(slot.windowSequence, closeSequence, now);
        }
    }

    private void abandonWindow(ClientViewPortalSlot<B> slot) {
        if (!slot.windowOpen) {
            return;
        }
        slot.windowOpen = false;
        ClientViewAckWindow active = window;
        if (active != null && active.abandon(slot.windowSequence) && paused) {
            paused = false;
            lane.submit();
        }
    }

    private void sendScene() {
        for (int i = 0; i < laneScene.size(); i++) {
            Scene<B> scene = laneScene.get(i);
            ClientViewPortalSlot<B> slot = scene.slot();
            if (!slot.laneAttached || !slot.announced || slot.standby) {
                resendScene(slot, scene.message());
                continue;
            }
            try {
                emit(List.of(scene.message()), false);
            } catch (ClientViewProtocolException failure) {
                resendScene(slot, scene.message());
                platform.warnings().accept("ClientView " + scene.message().type() + " failed for player " + playerId, failure);
            }
        }
        laneScene.clear();
    }

    private void sendBursts() {
        int size = laneBursts.size();
        for (int from = 0; from < size; from += ClientViewProtocol.MAX_FX_EMITTERS) {
            ClientViewMessage.Fx fx = new ClientViewMessage.Fx(ClientViewProtocol.WORLD_FX_KEY,
                laneBursts.subList(from, Math.min(size, from + ClientViewProtocol.MAX_FX_EMITTERS)));
            try {
                emit(List.of(fx), false);
            } catch (ClientViewProtocolException failure) {
                platform.warnings().accept("ClientView world FX failed for player " + playerId, failure);
                break;
            }
        }
        laneBursts.clear();
    }

    private static void resendScene(ClientViewPortalSlot<?> slot, ClientViewMessage message) {
        if (message instanceof ClientViewMessage.EntityFrame) {
            slot.needFullEntities = true;
        } else {
            slot.needFullScene = true;
        }
    }

    private void emitQuietly(List<ClientViewMessage> group) {
        try {
            emit(group, false);
        } catch (ClientViewProtocolException failure) {
            platform.warnings().accept("ClientView control frame failed for player " + playerId, failure);
        }
    }

    private int emit(List<ClientViewMessage> group, boolean close) throws ClientViewProtocolException {
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

    private void sendDirect(ClientViewMessage message) {
        try {
            byte[] frame = ClientViewCodec.encodeS2C(message, sequence.getAndIncrement(), ClientViewProtocol.FLAG_LAST);
            platform.transport().send(player, frame);
            platform.transport().flush(player);
            framesSent.incrementAndGet();
            bytesSent.addAndGet(frame.length);
        } catch (ClientViewProtocolException failure) {
            platform.warnings().accept("ClientView " + message.type() + " failed for player " + playerId, failure);
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

    private static int[] geometryPaletteIds(ClientPortalGeometry geometry) {
        if (geometry.nested().isEmpty()) {
            return new int[] {geometry.blackoutState()};
        }
        ArrayList<ClientPortalGeometry> pending = new ArrayList<ClientPortalGeometry>();
        pending.add(geometry);
        int[] ids = new int[4];
        int count = 0;
        while (!pending.isEmpty()) {
            ClientPortalGeometry next = pending.remove(pending.size() - 1);
            if (count == ids.length) {
                ids = Arrays.copyOf(ids, count * 2);
            }
            ids[count++] = next.blackoutState();
            pending.addAll(next.nested());
        }
        return Arrays.copyOf(ids, count);
    }

    private static boolean terminal(ClientViewMessage.ResetReason reason) {
        return reason == ClientViewMessage.ResetReason.DISABLED || reason == ClientViewMessage.ResetReason.PROTOCOL
            || reason == ClientViewMessage.ResetReason.OVERLOAD;
    }

    private enum Refresh {
        UNCHANGED,
        CHANGED,
        DETACH,
        REJECT
    }

    private sealed interface Command<B> permits Open, Attach, Detach, Miss, Refused, Scene, Burst, Reset {
    }

    private record Open<B>(ClientViewMessage.Accept accept) implements Command<B> {
    }

    private record Attach<B>(ClientViewPortalSlot<B> slot) implements Command<B> {
    }

    private record Detach<B>(ClientViewPortalSlot<B> slot) implements Command<B> {
    }

    private record Miss<B>(ClientViewMessage.BrickMiss.Plate miss) implements Command<B> {
    }

    private record Refused<B>(int portalKey) implements Command<B> {
    }

    private record Scene<B>(ClientViewPortalSlot<B> slot, ClientViewMessage message) implements Command<B> {
    }

    private record Burst<B>(ClientViewMessage.FxEmitter emitter) implements Command<B> {
    }

    private record Reset<B>(ClientViewMessage.ResetReason reason, boolean terminal) implements Command<B> {
    }
}
