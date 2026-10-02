package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.network.client.PlateSectionBox;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.client.ClientOverlapResolver;
import art.arcane.wormholes.render.client.ClientViewSweep;
import art.arcane.wormholes.render.plate.PlateBox;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntFunction;

public final class ClientViewTick implements ClientViewSession.Sink {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final int MAX_FRAMES_PER_TICK = 4096;

    private final ClientViewSession session;
    private final ClientViewReceiver receiver;
    private final WormholesClientConfig config;
    private final ClientViewStats stats;
    private final ClientOverlapResolver overlaps;
    private final List<ClientViewReceiver.Queued> drained;
    private final List<ClientPortal> order;
    private final List<ClientOverlapResolver.Contender> contenders;
    private final LongOpenHashSet touchedSections;
    private final LongOpenHashSet tickSections;
    private final LongArrayList sectionCells;
    private final LongArrayList overlaySectionCells;
    private final IntArrayList sectionPortals;
    private final LongOpenHashSet sectionSeen;
    private final List<ClientViewMessage.BrickMiss.Plate> misses;
    private final IntFunction<ClientPortal> portals;
    private final ClientLightPatches.CellLight cellLight;
    private final ClientNestedViews nested;
    private final Int2ObjectOpenHashMap<MirrorState> mirrors;
    private Consumer<ClientViewMessage> sender;
    private ClientViewSurface surface;
    private ProjectionOverlay overlay;
    private ClientProjectionApplier applier;
    private ClientLightPatches light;
    private ClientProjectedEntities entities;
    private ClientFxRunner fx;
    private ClientAtmosphere atmosphere;
    private int clientTick;
    private long protocolFailures;
    private boolean nativeModeApplied;
    private boolean ackDue;
    private int ackSequence;
    private long appliedSinceAck;
    private long sendFailures;
    private long handledSendFailures;
    private long handledDecodeFailures;
    private int nextRecoveryTick;
    private boolean handleFailureLogged;
    private boolean effectsActive = true;
    private boolean frameEffectsActive = true;
    private long effectsResumedAtNanos;

    public ClientViewTick(ClientViewSession session, ClientViewReceiver receiver, WormholesClientConfig config, ClientViewStats stats) {
        this.session = Objects.requireNonNull(session, "session");
        this.receiver = Objects.requireNonNull(receiver, "receiver");
        this.config = Objects.requireNonNull(config, "config");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.overlaps = new ClientOverlapResolver(config.hysteresisBlocks);
        this.drained = new ArrayList<>(64);
        this.order = new ArrayList<>(16);
        this.contenders = new ArrayList<>(2);
        this.touchedSections = new LongOpenHashSet(256);
        this.tickSections = new LongOpenHashSet(64);
        this.sectionCells = new LongArrayList(256);
        this.overlaySectionCells = new LongArrayList(256);
        this.sectionPortals = new IntArrayList(256);
        this.sectionSeen = new LongOpenHashSet(256);
        this.misses = new ArrayList<>(4);
        this.portals = session::portal;
        this.cellLight = new CellLight();
        this.nested = new ClientNestedViews(session, ClientViewProtocol.MAX_GEOMETRY_DEPTH, touchedSections);
        this.mirrors = new Int2ObjectOpenHashMap<>(4);
        this.sender = message -> { };
        this.effectsResumedAtNanos = System.nanoTime();
    }

    public void sender(Consumer<ClientViewMessage> value) {
        Consumer<ClientViewMessage> target = Objects.requireNonNull(value, "value");
        sender = message -> deliver(target, message);
    }

    public void attach(Object levelToken, ClientViewSurface levelSurface, ClientSceneWorld scene) {
        Objects.requireNonNull(levelToken, "levelToken");
        Objects.requireNonNull(levelSurface, "levelSurface");
        Objects.requireNonNull(scene, "scene");
        if (overlay != null) {
            ProjectionOverlay.deactivate(overlay);
        }
        surface = levelSurface;
        overlay = new ProjectionOverlay(levelToken);
        applier = new ClientProjectionApplier(surface, overlay, session.palette());
        light = new ClientLightPatches(surface);
        surface.attachLight(light);
        entities = new ClientProjectedEntities(scene);
        fx = new ClientFxRunner(scene);
        fx.particlesActive(effectsActive);
        atmosphere = new ClientAtmosphere(scene, config.atmosphereDominanceBlocks);
        nested.bind(applier, overlay);
        mirrors.clear();
        nativeModeApplied = false;
        ProjectionOverlay.activate(overlay);
        ObjectIterator<ClientPortal> portals = session.portals().values().iterator();
        while (portals.hasNext()) {
            ClientPortal portal = portals.next();
            ClientViewSweep sweep = portal.sweep();
            if (sweep != null) {
                sweep.clear();
                sweep.exited().clear();
            }
            portal.pendingEnters().clear();
            session.dirtyPortals().add(portal.portalKey());
        }
    }

    public void detach() {
        if (overlay != null) {
            ProjectionOverlay.deactivate(overlay);
        }
        if (surface != null && light != null) {
            surface.detachLight(light);
        }
        surface = null;
        overlay = null;
        applier = null;
        light = null;
        entities = null;
        fx = null;
        atmosphere = null;
        nested.unbind();
        mirrors.clear();
    }

    public boolean attached() {
        return surface != null;
    }

    public ProjectionOverlay overlay() {
        return overlay;
    }

    public ClientProjectionApplier applier() {
        return applier;
    }

    public ClientLightPatches light() {
        return light;
    }

    public ClientProjectedEntities entities() {
        return entities;
    }

    public ClientFxRunner fx() {
        return fx;
    }

    public ClientAtmosphere atmosphere() {
        return atmosphere;
    }

    public int clientTick() {
        return clientTick;
    }

    public long protocolFailures() {
        return protocolFailures;
    }

    public long sendFailures() {
        return sendFailures;
    }

    public void effectsActive(boolean active) {
        if (effectsActive == active) {
            return;
        }
        effectsActive = active;
        if (active) {
            effectsResumedAtNanos = System.nanoTime();
        }
        if (fx != null) {
            fx.particlesActive(active);
        }
    }

    public void tick(double eyeX, double eyeY, double eyeZ, double velocityX, double velocityY, double velocityZ, long nowMillis) {
        clientTick++;
        syncRendererSelection();
        drainFrames();
        syncRendererSelection();
        long decodeFailures = receiver.decodeFailures();
        if (sendFailures > handledSendFailures || session.nativeSelected() && decodeFailures > handledDecodeFailures) {
            handledSendFailures = sendFailures;
            handledDecodeFailures = decodeFailures;
            session.abandon(this);
        }
        if (session.state() == ClientViewSession.State.NATIVE_RECOVERING && clientTick >= nextRecoveryTick) {
            nextRecoveryTick = clientTick + 20;
            ClientViewMessage.Hello hello = session.recoveryHello();
            if (hello != null) {
                sender.accept(hello);
            }
        }
        if (!session.active()) {
            misses.clear();
            ackDue = false;
            appliedSinceAck = 0L;
            return;
        }
        if (surface == null) {
            sendMisses();
            sendAck(0L);
            return;
        }
        long applied = 0L;
        if (!session.nativeSelected()) {
            applied = sweepAll(eyeX, eyeY, eyeZ, velocityX, velocityY, velocityZ);
            assertLight();
            applier.flush();
            light.tick();
        }
        entities.tick(portals, key -> session.nativeSelected() || session.meshes().view(key) != null);
        fx.tick(portals);
        atmosphere.tick(eyeX, eyeY, eyeZ, portals, session.meshes(), effectsActive);
        sendMisses();
        sendAck(applied);
        if (session.has(ClientViewCapability.VIEW_STATS) && stats.reportDue(nowMillis)) {
            sender.accept(stats.report(nowMillis, clientTick, session.portals().size(), overlay.size(), session.palette().unknownStates(),
                session.memoryMb()));
        }
    }

    public void chunkReloaded(int chunkX, int chunkZ) {
        if (light == null) {
            return;
        }
        LongArrayList cells = overlay.keysInChunk(chunkX, chunkZ);
        for (int index = 0; index < cells.size(); index++) {
            long cell = cells.getLong(index);
            touchedSections.add(SectionPos.asLong(chunkX, ProjectionCellKey.unpackY(cell) >> 4, chunkZ));
        }
        ObjectIterator<MirrorState> states = mirrors.values().iterator();
        while (states.hasNext()) {
            MirrorState state = states.next();
            if (state.builder.sourceChunk(chunkX, chunkZ)) {
                state.builder.invalidateAll();
                state.full = true;
            }
        }
    }

    public void blockChanged(Object level, int x, int y, int z) {
        if (overlay == null || overlay.level() != level || overlay.writing() || mirrors.isEmpty()) {
            return;
        }
        ObjectIterator<MirrorState> states = mirrors.values().iterator();
        while (states.hasNext()) {
            MirrorState state = states.next();
            state.builder.sourceChanged(x, y, z, state.changes);
        }
    }

    public ClientNestedViews nestedViews() {
        return nested;
    }

    public ClientMirrorBuilder mirror(int portalKey) {
        MirrorState state = mirrors.get(portalKey);
        return state == null ? null : state.builder;
    }

    public IntArrayList mirrorKeys(IntArrayList out) {
        out.addAll(mirrors.keySet());
        return out;
    }

    public void revertEverything() {
        if (applier == null) {
            return;
        }
        nested.forget();
        mirrors.clear();
        applier.revertAll();
        light.clear();
        applier.flush();
        entities.clear();
        fx.clear();
        atmosphere.clear();
    }

    @Override
    public void meshStarted(ClientPortal portal) {
        if (applier != null) {
            touchedSections.addAll(light.sectionsOf(portal.portalKey()));
            applier.revertPortal(portal.portalKey());
            nested.drop(portal.portalKey());
        }
    }

    @Override
    public void meshAck(ClientViewMessage.MeshAck ack) {
        sender.accept(ack);
    }

    @Override
    public void brickMiss(ClientViewMessage.BrickMiss.Plate plate) {
        misses.add(plate);
    }

    @Override
    public void refused(ClientViewMessage.PlateRefused refused) {
        sender.accept(refused);
    }

    @Override
    public void dropped(ClientPortal portal) {
        if (applier == null) {
            return;
        }
        mirrors.remove(portal.portalKey());
        nested.drop(portal.portalKey());
        touchedSections.addAll(light.sectionsOf(portal.portalKey()));
        applier.revertPortal(portal.portalKey());
        reclaimFromOthers(portal.portalKey());
        entities.drop(portal.portalKey());
        fx.drop(portal.portalKey());
        atmosphere.drop(portal.portalKey());
    }

    @Override
    public void entities(ClientViewMessage.EntityFrame frame) {
        if (entities != null) {
            entities.apply(frame);
        }
    }

    @Override
    public void entityEvent(ClientViewMessage.EntityEvent event) {
        if (entities != null) {
            entities.apply(event);
        }
    }

    @Override
    public void fx(ClientViewMessage.Fx message) {
        if (fx != null) {
            fx.apply(message, portals, frameEffectsActive);
        }
    }

    @Override
    public void atmosphere(ClientViewMessage.Atmosphere message) {
        if (atmosphere != null && atmosphere.apply(message)) {
            touchedSections.addAll(light.sectionsOf(message.portalKey()));
        }
    }

    @Override
    public void reset(ClientViewMessage.ResetReason reason) {
        misses.clear();
        revertEverything();
        stats.reset();
    }

    @Override
    public void restarted() {
        misses.clear();
        ackDue = false;
        appliedSinceAck = 0L;
        revertEverything();
        stats.reset();
        sendFailures = 0L;
        handledSendFailures = 0L;
        handledDecodeFailures = receiver.decodeFailures();
        nextRecoveryTick = 0;
        handleFailureLogged = false;
    }

    private void syncRendererSelection() {
        if (!session.nativeSelected()) {
            nativeModeApplied = false;
            return;
        }
        if (nativeModeApplied) {
            return;
        }
        revertEverything();
        session.clearPlateContent();
        touchedSections.clear();
        misses.clear();
        appliedSinceAck = 0L;
        nativeModeApplied = true;
    }

    private void drainFrames() {
        drained.clear();
        receiver.drain(drained, MAX_FRAMES_PER_TICK);
        for (int index = 0; index < drained.size(); index++) {
            syncRendererSelection();
            ClientViewReceiver.Queued queued = drained.get(index);
            stats.frame(queued.bytes());
            frameEffectsActive = queued.receivedNanos() - effectsResumedAtNanos >= 0L;
            try {
                session.handle(queued.frame().message(), this);
            } catch (ClientViewProtocolException failure) {
                protocolFailures++;
                if (session.nativeSelected()) {
                    session.abandon(this);
                }
            } catch (RuntimeException failure) {
                protocolFailures++;
                if (session.nativeSelected()) {
                    session.abandon(this);
                }
                if (!handleFailureLogged) {
                    handleFailureLogged = true;
                    LOGGER.warn("Wormholes ClientView dropped a {} message it could not apply", queued.frame().message().type(), failure);
                }
            } finally {
                frameEffectsActive = true;
            }
            if (queued.frame().last() && session.active()) {
                int seq = queued.frame().seq();
                if (!ackDue || seq - ackSequence > 0) {
                    ackSequence = seq;
                }
                ackDue = true;
            }
        }
        drained.clear();
    }

    private void deliver(Consumer<ClientViewMessage> target, ClientViewMessage message) {
        try {
            target.accept(message);
        } catch (RuntimeException failure) {
            sendFailures++;
            if (sendFailures == 1L) {
                LOGGER.warn("Wormholes ClientView could not send {} to the server; native views will retry when the connection is available",
                    message.type(), failure);
            }
        }
    }

    private long sweepAll(double eyeX, double eyeY, double eyeZ, double velocityX, double velocityY, double velocityZ) {
        syncMirrors();
        order.clear();
        ObjectIterator<ClientPortal> portals = session.portals().values().iterator();
        while (portals.hasNext()) {
            ClientPortal portal = portals.next();
            if (portal.ready() && !portal.nested() && session.meshes().view(portal.portalKey()) == null) {
                order.add(portal);
            }
        }
        order.sort(Comparator.comparingDouble(ClientPortal::eyeDistance));
        long appliedCells = 0L;
        for (int index = 0; index < order.size(); index++) {
            ClientPortal portal = order.get(index);
            ClientViewSweep sweep = portal.sweep();
            long refreshStart = System.nanoTime();
            if (!sweep.exited().isEmpty()) {
                processExits(portal, sweep.exited());
            }
            appliedCells += refreshContent(portal);
            long sweepStart = System.nanoTime();
            boolean changed = sweep.sweep(eyeX, eyeY, eyeZ, velocityX, velocityY, velocityZ);
            stats.sweep(System.nanoTime() - sweepStart);
            long applyStart = System.nanoTime();
            if (changed || !portal.pendingEnters().isEmpty()) {
                appliedCells += processExits(portal, sweep.exited());
                appliedCells += processEnters(portal, sweep.entered(), sweep.reshelled());
            }
            appliedCells += refreshMirror(portal);
            stats.apply((sweepStart - refreshStart) + (System.nanoTime() - applyStart));
        }
        if (session.has(ClientViewCapability.CLIENT_RECURSION) && config.clientRecursion) {
            appliedCells += nested.update(order, eyeX + velocityX, eyeY + velocityY, eyeZ + velocityZ);
        }
        session.dirtyPortals().clear();
        stats.appliedCells(appliedCells);
        return appliedCells;
    }

    private int refreshContent(ClientPortal portal) {
        if (portal.contentDirty()) {
            int reverted = refreshPortal(portal);
            portal.contentClean();
            return reverted;
        }
        if (!portal.touchedBricks().isEmpty()) {
            refreshBricks(portal);
            portal.contentClean();
        }
        return 0;
    }

    private int refreshPortal(ClientPortal portal) {
        LongArrayList keys = overlay.keysOf(portal.portalKey());
        ClientViewSweep sweep = portal.sweep();
        int reverted = 0;
        for (int index = 0; index < keys.size(); index++) {
            long key = keys.getLong(index);
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            if (!sweep.applied(x, y, z) && applier.exit(key, portal.portalKey())) {
                touchedSections.add(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
                reverted++;
            }
        }
        touchedSections.addAll(light.sectionsOf(portal.portalKey()));
        sweep.appliedKeys(portal.pendingEnters());
        return reverted;
    }

    private void refreshBricks(ClientPortal portal) {
        ClientPlate plate = portal.plate();
        PlateSectionBox sections = plate.sections();
        ClientViewSweep sweep = portal.sweep();
        PlateBox bounds = sweep.bounds();
        LongArrayList pending = portal.pendingEnters();
        IntIterator bricks = portal.touchedBricks().iterator();
        while (bricks.hasNext()) {
            int brick = bricks.nextInt();
            int sectionX = sections.sectionX(brick);
            int sectionY = sections.sectionY(brick);
            int sectionZ = sections.sectionZ(brick);
            touchedSections.add(SectionPos.asLong(sectionX, sectionY, sectionZ));
            int minX = Math.max(sectionX << 4, bounds.minX());
            int minY = Math.max(sectionY << 4, bounds.minY());
            int minZ = Math.max(sectionZ << 4, bounds.minZ());
            int maxX = Math.min((sectionX << 4) + 15, bounds.minX() + bounds.sizeX() - 1);
            int maxY = Math.min((sectionY << 4) + 15, bounds.minY() + bounds.sizeY() - 1);
            int maxZ = Math.min((sectionZ << 4) + 15, bounds.minZ() + bounds.sizeZ() - 1);
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (sweep.applied(x, y, z)) {
                            pending.add(ProjectionCellKey.pack(x, y, z));
                        }
                    }
                }
            }
        }
    }

    private int processExits(ClientPortal portal, LongArrayList exited) {
        int reverted = 0;
        for (int index = 0; index < exited.size(); index++) {
            long key = exited.getLong(index);
            touchedSections.add(SectionPos.asLong(ProjectionCellKey.unpackX(key) >> 4, ProjectionCellKey.unpackY(key) >> 4,
                ProjectionCellKey.unpackZ(key) >> 4));
            if (applier.exit(key, portal.portalKey())) {
                reverted++;
                reenterOthers(key, portal.portalKey());
            }
        }
        exited.clear();
        return reverted;
    }

    private int processEnters(ClientPortal portal, LongArrayList entered, LongArrayList reshelled) {
        LongArrayList pending = portal.pendingEnters();
        int applied = 0;
        int budget = config.sectionsPerTick <= 0 ? Integer.MAX_VALUE : config.sectionsPerTick;
        tickSections.clear();
        if (!pending.isEmpty()) {
            LongArrayList carried = new LongArrayList(pending);
            pending.clear();
            applied += enterKeys(portal, carried, budget);
        }
        applied += enterKeys(portal, entered, budget);
        applied += enterKeys(portal, reshelled, budget);
        return applied;
    }

    private int enterKeys(ClientPortal portal, LongArrayList keys, int budget) {
        ClientViewSweep sweep = portal.sweep();
        int applied = 0;
        for (int index = 0; index < keys.size(); index++) {
            long key = keys.getLong(index);
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            long section = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
            if (!tickSections.contains(section) && tickSections.size() >= budget) {
                portal.pendingEnters().add(key);
                continue;
            }
            if (!sweep.applied(x, y, z)) {
                continue;
            }
            if (!claim(key, portal, x, y, z)) {
                continue;
            }
            touchedSections.add(section);
            if (applier.enter(key, portal.portalKey(), portal.content(), portal.policy(), sweep.shell(x, y, z))) {
                tickSections.add(section);
                applied++;
            }
        }
        return applied;
    }

    private boolean claim(long key, ClientPortal portal, int x, int y, int z) {
        ProjectionOverlay.Entry entry = overlay.get(key);
        if (entry == null || entry.portalKey() == portal.portalKey()) {
            return true;
        }
        if (nested.owns(entry.portalKey())) {
            return false;
        }
        ClientPortal incumbent = session.portal(entry.portalKey());
        if (incumbent == null || !incumbent.ready()) {
            entry.portalKey(portal.portalKey());
            return true;
        }
        contenders.clear();
        contenders.add(contender(portal, x, y, z));
        contenders.add(contender(incumbent, x, y, z));
        int winner = overlaps.resolve(contenders, incumbent.portalKey());
        if (winner != portal.portalKey()) {
            return false;
        }
        entry.portalKey(portal.portalKey());
        return true;
    }

    private void reenterOthers(long key, int releasedBy) {
        int x = ProjectionCellKey.unpackX(key);
        int y = ProjectionCellKey.unpackY(key);
        int z = ProjectionCellKey.unpackZ(key);
        ClientPortal best = null;
        for (int index = 0; index < order.size(); index++) {
            ClientPortal candidate = order.get(index);
            if (candidate.portalKey() == releasedBy || !candidate.ready() || !candidate.sweep().applied(x, y, z)) {
                continue;
            }
            if (best == null || ClientOverlapResolver.isHigherPriority(contender(candidate, x, y, z), contender(best, x, y, z))) {
                best = candidate;
            }
        }
        if (best != null && applier.enter(key, best.portalKey(), best.content(), best.policy(), best.sweep().shell(x, y, z))) {
            touchedSections.add(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        }
    }

    private void reclaimFromOthers(int droppedPortal) {
        ObjectIterator<ClientPortal> portals = session.portals().values().iterator();
        while (portals.hasNext()) {
            ClientPortal portal = portals.next();
            if (portal.portalKey() != droppedPortal && portal.ready()) {
                session.dirtyPortals().add(portal.portalKey());
                portal.markDirty();
            }
        }
    }

    private ClientOverlapResolver.Contender contender(ClientPortal portal, int x, int y, int z) {
        boolean maskAir = portal.content().paletteIdAt(x, y, z) == ClientViewProtocol.PALETTE_AIR;
        return new ClientOverlapResolver.Contender(portal.portalKey(), portal.sweep().eyeDot(), maskAir);
    }

    private void assertLight() {
        if (touchedSections.isEmpty()) {
            return;
        }
        if (!session.has(ClientViewCapability.DEST_LIGHT)) {
            touchedSections.clear();
            return;
        }
        for (long section : touchedSections) {
            int sectionX = SectionPos.x(section);
            int sectionY = SectionPos.y(section);
            int sectionZ = SectionPos.z(section);
            sectionCells.clear();
            sectionPortals.clear();
            sectionSeen.clear();
            overlaySectionCells.clear();
            overlay.appendSectionKeys(sectionX, sectionY, sectionZ, overlaySectionCells);
            for (int index = 0; index < overlaySectionCells.size(); index++) {
                long cell = overlaySectionCells.getLong(index);
                ProjectionOverlay.Entry entry = overlay.get(cell);
                if (entry == null || entry.pending()) {
                    continue;
                }
                sectionSeen.add(cell);
                sectionCells.add(cell);
                sectionPortals.add(entry.portalKey());
            }
            for (int index = 0; index < order.size(); index++) {
                collectConeCells(order.get(index), sectionX, sectionY, sectionZ);
            }
            light.refresh(sectionX, sectionY, sectionZ, sectionCells, sectionPortals, cellLight);
        }
        touchedSections.clear();
    }

    private void collectConeCells(ClientPortal portal, int sectionX, int sectionY, int sectionZ) {
        if (portal.plate() == null) {
            return;
        }
        ClientViewSweep sweep = portal.sweep();
        PlateBox bounds = sweep.bounds();
        int minX = Math.max(sectionX << 4, bounds.minX());
        int minY = Math.max(sectionY << 4, bounds.minY());
        int minZ = Math.max(sectionZ << 4, bounds.minZ());
        int maxX = Math.min((sectionX << 4) + 15, bounds.minX() + bounds.sizeX() - 1);
        int maxY = Math.min((sectionY << 4) + 15, bounds.minY() + bounds.sizeY() - 1);
        int maxZ = Math.min((sectionZ << 4) + 15, bounds.minZ() + bounds.sizeZ() - 1);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return;
        }
        int portalKey = portal.portalKey();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (!sweep.applied(x, y, z)) {
                        continue;
                    }
                    long key = ProjectionCellKey.pack(x, y, z);
                    if (!sectionSeen.add(key)) {
                        continue;
                    }
                    sectionCells.add(key);
                    sectionPortals.add(portalKey);
                }
            }
        }
    }

    private void syncMirrors() {
        boolean enabled = session.has(ClientViewCapability.CLIENT_MIRROR) && config.clientMirror && overlay != null;
        ObjectIterator<Int2ObjectMap.Entry<MirrorState>> states = mirrors.int2ObjectEntrySet().fastIterator();
        while (states.hasNext()) {
            Int2ObjectMap.Entry<MirrorState> entry = states.next();
            ClientPortal portal = session.portal(entry.getIntKey());
            if (!enabled || portal == null || portal.plate() != null || portal.geometry() != entry.getValue().builder.geometry()) {
                states.remove();
            }
        }
        if (!enabled) {
            return;
        }
        ObjectIterator<ClientPortal> portals = session.portals().values().iterator();
        while (portals.hasNext()) {
            ClientPortal portal = portals.next();
            if (!portal.geometry().mirror() || portal.nested() || portal.plate() != null || mirrors.containsKey(portal.portalKey())) {
                continue;
            }
            ClientMirrorBuilder builder = ClientMirrorBuilder.create(portal.geometry(), session.palette(), this::shadow);
            if (builder == null) {
                continue;
            }
            mirrors.put(portal.portalKey(), new MirrorState(builder));
            if (portal.content() != null) {
                applier.revertPortal(portal.portalKey());
            }
            portal.content(builder);
            ClientViewSweep sweep = portal.sweep();
            sweep.clear();
            sweep.exited().clear();
        }
    }

    private int refreshMirror(ClientPortal portal) {
        MirrorState state = mirrors.get(portal.portalKey());
        if (state == null) {
            return 0;
        }
        LongArrayList keys = state.changes;
        if (state.full) {
            keys.clear();
            portal.sweep().appliedKeys(keys);
            state.full = false;
        }
        if (keys.isEmpty()) {
            return 0;
        }
        ClientViewSweep sweep = portal.sweep();
        int refreshed = 0;
        int count = keys.size();
        for (int index = 0; index < count; index++) {
            long key = keys.getLong(index);
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            if (!sweep.applied(x, y, z)) {
                continue;
            }
            ProjectionOverlay.Entry entry = overlay.get(key);
            if (entry != null && entry.portalKey() != portal.portalKey()) {
                continue;
            }
            if (applier.enter(key, portal.portalKey(), state.builder, portal.policy(), sweep.shell(x, y, z))) {
                touchedSections.add(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
                refreshed++;
            }
        }
        keys.removeElements(0, count);
        return refreshed;
    }

    private BlockState shadow(int x, int y, int z) {
        ProjectionOverlay current = overlay;
        ProjectionOverlay.Entry entry = current == null ? null : current.get(ProjectionCellKey.pack(x, y, z));
        if (entry != null) {
            return entry.pending() ? null : entry.shadow();
        }
        ClientViewSurface level = surface;
        return level == null ? null : level.state(x, y, z);
    }

    private void sendAck(long appliedCells) {
        appliedSinceAck += appliedCells;
        if (!ackDue) {
            return;
        }
        sender.accept(new ClientViewMessage.Ack(ackSequence, clientTick, (int) Math.min(Integer.MAX_VALUE, appliedSinceAck)));
        stats.ack();
        ackDue = false;
        appliedSinceAck = 0L;
    }

    private void sendMisses() {
        if (misses.isEmpty()) {
            return;
        }
        int from = 0;
        int bytes = ClientViewProtocol.C2S_HEADER_BYTES + 1;
        for (int index = 0; index < misses.size(); index++) {
            int size = misses.get(index).wireBytes();
            if (index > from && (bytes + size > ClientViewProtocol.MAX_C2S_BYTES || index - from == ClientViewProtocol.MAX_BRICK_MISS_PLATES)) {
                sender.accept(new ClientViewMessage.BrickMiss(misses.subList(from, index)));
                stats.brickMiss();
                from = index;
                bytes = ClientViewProtocol.C2S_HEADER_BYTES + 1;
            }
            bytes += size;
        }
        sender.accept(new ClientViewMessage.BrickMiss(misses.subList(from, misses.size())));
        stats.brickMiss();
        misses.clear();
    }

    private static final class MirrorState {
        private final ClientMirrorBuilder builder;
        private final LongArrayList changes;
        private boolean full;

        private MirrorState(ClientMirrorBuilder builder) {
            this.builder = builder;
            this.changes = new LongArrayList(64);
        }
    }

    private final class CellLight implements ClientLightPatches.CellLight {
        private final int[] content = new int[3];

        @Override
        public int light(long cellKey, int portalKey) {
            ClientPortal portal = session.portal(portalKey);
            ClientPlate plate = portal == null ? null : portal.plate();
            if (plate == null) {
                return ClientLightPatches.NO_LIGHT;
            }
            nested.contentCell(portalKey, ProjectionCellKey.unpackX(cellKey), ProjectionCellKey.unpackY(cellKey),
                ProjectionCellKey.unpackZ(cellKey), content);
            int brickIndex = plate.brickIndexOf(content[0], content[1], content[2]);
            if (brickIndex < 0 || !plate.hasLight(brickIndex)) {
                return ClientLightPatches.NO_LIGHT;
            }
            int cell = ClientViewProtocol.brickCellIndex(content[0], content[1], content[2]);
            int sky = Math.max(0, BrickLightSource.nibble(plate.skyLight(brickIndex), cell) - atmosphere.skyDarken(portalKey));
            return (sky << 4) | BrickLightSource.nibble(plate.blockLight(brickIndex), cell);
        }
    }
}
