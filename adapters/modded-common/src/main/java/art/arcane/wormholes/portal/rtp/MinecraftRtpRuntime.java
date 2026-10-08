package art.arcane.wormholes.portal.rtp;

import art.arcane.optics.crossing.Pose;
import art.arcane.optics.crossing.PoseTransform;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.api.traversal.TraversalKind;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.chunk.presend.ChunkPreSendTicket;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.modded.MinecraftArrivalPose;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftMenuText;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.optics.math.Box;
import net.minecraft.core.particles.DustParticleOptions;
import art.arcane.wormholes.modded.MinecraftTravelCosts;
import art.arcane.wormholes.modded.MinecraftTraversalCues;
import art.arcane.wormholes.modded.MinecraftTransit;
import art.arcane.wormholes.modded.MinecraftTraversalContext;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.portal.Portal;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.wormholes.portal.PortalStateCodec;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MinecraftRtpRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final WormholesModRuntime runtime;
    private final Map<UUID, Registration> registrations = new HashMap<>();
    private final Map<UUID, Active> traversals = new HashMap<>();
    private final Map<ViewKey, View> views = new HashMap<>();
    private final RtpSafetyValidator safety = new RtpSafetyValidator();
    private MinecraftServer server;
    private MinecraftRtpCandidateLoader candidates;
    private ExecutorService searches;
    private RtpService service;
    private RtpRimRenderer rims;
    private boolean closed = true;

    public MinecraftRtpRuntime(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public void start() {
        runtime.requireServerThread();
        server = runtime.server();
        candidates = new MinecraftRtpCandidateLoader(runtime);
        searches = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().name("Wormholes-rtp-search-", 0).factory());
        closed = false;
        rims = new RtpRimRenderer();
        service = new RtpService(new RtpService.Dependencies(new Dispatcher(), searches::execute, System::currentTimeMillis,
            (registration, generation, attempt) -> new RtpSampler(registration.centerX(), registration.centerZ(), registration.seed())
                .sample(registration.settings(), generation, attempt), candidates, safety::validate, this::access, this::projection, rims));
    }

    public void tick() {
        runtime.requireServerThread();
        if (closed) {
            return;
        }
        Set<UUID> current = new HashSet<>();
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            if (portal.getType() == PortalType.RTP && portal.isOpen() && !portal.isMirrorMode()) {
                current.add(portal.getId());
                synchronize(portal);
                observe(service.tick(portal.getId()), "maintain", portal.getId());
            }
        }
        for (UUID id : List.copyOf(registrations.keySet())) {
            if (!current.contains(id)) {
                unregister(id);
            }
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<ViewKey, View> entry : List.copyOf(views.entrySet())) {
            if (now - entry.getValue().touched > 1500L) {
                views.remove(entry.getKey());
                observe(service.leaveViewer(entry.getKey().portal(), entry.getKey().viewer()), "leave", entry.getKey().portal());
            }
        }
        for (Active active : List.copyOf(traversals.values())) {
            hold(active, now);
        }
    }

    public RtpSettings settings(MinecraftPortal portal) {
        runtime.requireServerThread();
        synchronize(portal);
        return registrations.get(portal.getId()).settings();
    }

    public Optional<RtpService.Snapshot> snapshot(UUID portal) {
        return closed ? Optional.empty() : service.snapshot(portal);
    }

    public CompletableFuture<Boolean> reroll(UUID portal) {
        runtime.requireServerThread();
        return service.manualReroll(portal);
    }

    public CompletableFuture<Set<RtpDestination>> rebuild(UUID portal) {
        runtime.requireServerThread();
        return service.rebuildPool(portal);
    }

    public MinecraftPortal projectionDestination(ServerPlayer viewer, MinecraftPortal portal) {
        runtime.requireServerThread();
        if (closed || portal.getType() != PortalType.RTP) {
            return null;
        }
        synchronize(portal);
        ViewKey key = new ViewKey(portal.getId(), viewer.getUUID());
        View view = views.computeIfAbsent(key, ignored -> new View());
        view.touched = System.currentTimeMillis();
        observe(service.touchViewer(portal.getId(), viewer.getUUID()), "view", portal.getId());
        RtpProjectionView.ReadyData ready = service.projectionView(portal.getId(), viewer.getUUID()).readyFor(viewer.getUUID()).orElse(null);
        rim(viewer, portal);
        if (ready == null) {
            view.destination = null;
            view.ready = null;
            retirePlate(portal, view, 0L);
            return null;
        }
        if (!ready.equals(view.ready)) {
            view.destination = descriptor(portal, ready);
            view.ready = ready;
            retirePlate(portal, view, plateIdentity(portal, ready));
        }
        return view.destination;
    }

    public MinecraftPortal knownDestination(ServerPlayer viewer, MinecraftPortal portal) {
        runtime.requireServerThread();
        if (closed || portal.getType() != PortalType.RTP) {
            return null;
        }
        View view = views.get(new ViewKey(portal.getId(), viewer.getUUID()));
        return view == null ? null : view.destination;
    }

    public long plateIdentity(ServerPlayer viewer, MinecraftPortal portal) {
        runtime.requireServerThread();
        if (closed) {
            return 0L;
        }
        View view = views.get(new ViewKey(portal.getId(), viewer.getUUID()));
        return view == null ? 0L : view.plateIdentity;
    }

    static long plateIdentity(MinecraftPortal source, RtpProjectionView.ReadyData ready) {
        RtpProjectionView.Point3 feet = ready.target().safeFeet();
        UUID worldId = UUID.nameUUIDFromBytes(ready.target().worldKey().getBytes(StandardCharsets.UTF_8));
        return RtpProjectionGeometry.plateIdentity(worldId, feet.x(), feet.y(), feet.z(),
            RtpProjectionGeometry.targetFrameFor(source.getFrame()), ready.routeRevision());
    }

    private void retirePlate(MinecraftPortal portal, View view, long identity) {
        if (view.plateIdentity != 0L && view.plateIdentity != identity) {
            runtime.projections().plates().invalidateTarget(portal.getId(), view.plateIdentity);
        }
        view.plateIdentity = identity;
    }

    public boolean locked(UUID entity) {
        return traversals.containsKey(entity);
    }

    public boolean begin(Entity entity, MinecraftPortal portal, PlaneCrossing crossing) {
        runtime.requireServerThread();
        if (closed || traversals.containsKey(entity.getUUID()) || !eligible(entity, portal)) {
            return false;
        }
        if (!runtime.rules().depart(entity, portal, () -> begin(entity, portal, crossing))) {
            return false;
        }
        synchronize(portal);
        if (entity instanceof ServerPlayer player) {
            observe(service.touchViewer(portal.getId(), player.getUUID()), "traverse-view", portal.getId());
        }
        UUID claim = UUID.randomUUID();
        RtpService.TraversalActor actor = entity instanceof ServerPlayer player ? RtpService.TraversalActor.player(claim, player.getUUID())
            : RtpService.TraversalActor.anonymous(claim);
        Active active = new Active(entity, portal, crossing, registrations.get(portal.getId()));
        traversals.put(entity.getUUID(), active);
        service.claimTraversal(portal.getId(), actor).whenComplete((preparation, error) -> server.execute(() -> {
            if (error != null) {
                LOGGER.error("Could not claim random destination for portal {}", portal.getId(), error);
                cancel(active, TraversalRefundReason.DESTINATION_UNAVAILABLE, true, "claim failed");
                return;
            }
            if (preparation.isEmpty()) {
                if (entity instanceof ServerPlayer player && !active.finished) {
                    player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.PORTAL_RTP_NOT_READY, Map.of()));
                }
                cancel(active, TraversalRefundReason.DESTINATION_UNAVAILABLE, true, "no destination ready");
                return;
            }
            active.preparation = preparation.get();
            if (active.finished) {
                observe(service.completeTraversal(active.preparation, false), "cancel", portal.getId());
                return;
            }
            if (!valid(active)) {
                cancel(active, TraversalRefundReason.TRAVERSAL_ABORTED, true, "traveler moved before load");
                return;
            }
            RtpDestination destination = active.preparation.claim().destination();
            candidates.exact(new RtpService.SearchRequest(portal.getId(), active.preparation.generation(), active.registration.settings(), destination),
                envelope(entity)).whenComplete((loaded, failure) -> server.execute(() -> arrive(active, loaded, failure)));
        }));
        return true;
    }

    public void disconnected(ServerPlayer player) {
        runtime.requireServerThread();
        Active active = traversals.get(player.getUUID());
        if (active != null) {
            cancel(active, TraversalRefundReason.TRAVELER_LEFT, false, "traveler disconnected");
        }
        for (ViewKey key : List.copyOf(views.keySet())) {
            if (key.viewer().equals(player.getUUID())) {
                views.remove(key);
                observe(service.leaveViewer(key.portal(), key.viewer()), "disconnect", key.portal());
            }
        }
        rims.forgetViewer(player.getUUID());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        runtime.requireServerThread();
        for (Active active : List.copyOf(traversals.values())) {
            cancel(active, TraversalRefundReason.SERVER_SHUTDOWN, false, "runtime closed");
        }
        for (UUID id : List.copyOf(registrations.keySet())) {
            unregister(id);
        }
        closed = true;
        views.clear();
        candidates.close();
        searches.shutdownNow();
    }

    private void synchronize(MinecraftPortal portal) {
        Registration previous = registrations.get(portal.getId());
        Object stored = portal.setting("rtp");
        if (previous != null && previous.stored() == stored && previous.portal() == portal) {
            return;
        }
        ServerLevel source = runtime.portals().resolveLevel(portal);
        if (source == null) {
            throw new IllegalStateException("RTP source world is unavailable");
        }
        Map<String, Object> values = stored instanceof Map<?, ?> ? PortalStateCodec.object(Map.of("rtp", stored), "rtp") : Map.of();
        RtpSettings settings = RtpSettingsCodec.readSettings(values, key -> world(key == null ? source : candidates.level(key)));
        Registration registration = new Registration(portal, stored, settings);
        registrations.put(portal.getId(), registration);
        double x = settings.getCenterMode() == RtpCenterMode.CUSTOM ? settings.getCustomCenterX() : portal.getOrigin().x();
        double z = settings.getCenterMode() == RtpCenterMode.CUSTOM ? settings.getCustomCenterZ() : portal.getOrigin().z();
        for (Active active : List.copyOf(traversals.values())) {
            if (active.portal == portal) {
                cancel(active, TraversalRefundReason.TRAVERSAL_ABORTED, true, "portal settings changed");
            }
        }
        observe(service.register(new RtpService.Registration(portal.getId(), settings, x, z,
            portal.getId().getMostSignificantBits() ^ portal.getId().getLeastSignificantBits())), "register", portal.getId());
    }

    private void unregister(UUID id) {
        registrations.remove(id);
        views.keySet().removeIf(key -> key.portal().equals(id));
        rims.forgetPortal(id);
        for (Active active : List.copyOf(traversals.values())) {
            if (active.portal.getId().equals(id)) {
                cancel(active, TraversalRefundReason.TRAVERSAL_ABORTED, true, "portal unregistered");
            }
        }
        observe(service.unregister(id), "unregister", id);
    }

    private CompletableFuture<RtpAccessResult> access(UUID portalId, Optional<UUID> viewerId, RtpDestination destination) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        ServerPlayer player = viewerId.map(id -> server.getPlayerList().getPlayer(id)).orElse(null);
        boolean allowed = portal != null && portal.isOpen() && candidates.level(destination.worldKey()) != null
            && (viewerId.isEmpty() || player != null && runtime.portals().canDepart(player, portal));
        return CompletableFuture.completedFuture(allowed ? RtpAccessResult.allowedResult() : RtpAccessResult.deniedResult());
    }

    private RtpProjectionView.ReadyData projection(UUID portalId, UUID viewerId, RtpDestination destination, long revision) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        return RtpProjectionGeometry.create(new RtpProjectionGeometry.Source(portalId, portal.getWorldKey(), portal.getOrigin(),
            portal.getFrame(), portal.getGeometry().getArea(), portal.getGeometry().getRevision()), destination, revision);
    }

    private MinecraftPortal descriptor(MinecraftPortal source, RtpProjectionView.ReadyData ready) {
        Frame frame = RtpProjectionGeometry.targetFrameFor(source.getFrame());
        RtpProjectionView.Point3 point = ready.target().safeFeet();
        Vec3d origin = new Vec3d(point.x(), point.y(), point.z());
        ApertureCells geometry = new ApertureCells();
        List<Vec3d> cells = new ArrayList<>();
        OpticTransform transform = OpticTransform.between(source.getFrame(), source.getOrigin(), frame, origin);
        for (Vec3d block : source.getGeometry().getBlockPositions()) {
            cells.add(transform.point(block.add(new Vec3d(0.5, 0.5, 0.5))));
        }
        geometry.setBlocks(cells);
        return new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(ready.routeId(), origin, source.getName(), frame, true),
            geometry, ready.target().worldKey(), source.write()));
    }

    public RtpRimRenderer.Sample rimSample(ServerPlayer viewer, MinecraftPortal portal) {
        runtime.requireServerThread();
        if (closed || portal.getType() != PortalType.RTP || !runtime.configuration().settings().getMain().enableParticles) {
            return null;
        }
        RimState state = rimState(portal);
        return state == null ? null : service.rimSample(portal.getId(), viewer.getUUID(), state.phase(), state.elapsed()).orElse(null);
    }

    private RimState rimState(MinecraftPortal portal) {
        RtpService.Snapshot snapshot = service.snapshot(portal.getId()).orElse(null);
        if (snapshot == null) {
            return null;
        }
        RtpRuntimeSnapshot state = snapshot.runtime();
        RtpRimRenderer.Phase phase = state.ready() ? RtpRimRenderer.Phase.READY
            : state.sharedClaims() + state.playerClaims() + state.anonymousClaims() > 0 ? RtpRimRenderer.Phase.CLOSING : RtpRimRenderer.Phase.PREPARING;
        long duration = state.rotationMode() == RtpRotationMode.TIMED ? snapshot.settings().getCycleDurationMillis() : 0L;
        long elapsed = duration == 0L || state.nextRotationAtMillis() <= 0L ? 0L
            : Math.max(0L, duration - Math.max(0L, state.nextRotationAtMillis() - System.currentTimeMillis()));
        return new RimState(phase, elapsed);
    }

    private void rim(ServerPlayer viewer, MinecraftPortal portal) {
        if (!runtime.configuration().settings().getMain().enableParticles || runtime.clientViews().receiver(viewer)) {
            return;
        }
        RimState state = rimState(portal);
        if (state == null) {
            return;
        }
        RtpRimRenderer.Sample sample = service.rimDispatch(portal.getId(), viewer.getUUID(), state.phase(), state.elapsed(),
            server.getTickCount(), FidelitySettings.rtpRimIntervalTicks).orElse(null);
        if (sample == null) {
            return;
        }
        RtpRimRenderer.Color color = sample.color();
        DustParticleOptions particle = new DustParticleOptions(color.red() << 16 | color.green() << 8 | color.blue(), 1F);
        Box area = portal.getGeometry().getArea();
        for (int corner = 0; corner < 8; corner++) {
            viewer.level().sendParticles(viewer, particle, false, false, (corner & 1) == 0 ? area.getXa() : area.getXb(),
                (corner & 2) == 0 ? area.getYa() : area.getYb(), (corner & 4) == 0 ? area.getZa() : area.getZb(), 1, 0, 0, 0, 0);
        }
    }

    private boolean eligible(Entity entity, MinecraftPortal portal) {
        if (!entity.isAlive() || entity.isRemoved() || entity.isPassenger() || !entity.getPassengers().isEmpty() || portal.getType() != PortalType.RTP || !portal.isOpen()
            || portal.isMirrorMode() || runtime.portals().get(portal.getId()) != portal) {
            return false;
        }
        AABB bounds = entity.getBoundingBox();
        if (bounds.maxX <= bounds.minX || bounds.maxY <= bounds.minY || bounds.maxZ <= bounds.minZ
            || entity.level() != runtime.portals().resolveLevel(portal)
            || !portal.getGeometry().captureZone(2D).containsPrimitive(entity.getX(), entity.getY(), entity.getZ())) {
            return false;
        }
        for (Entity member : entity.getSelfAndPassengers().toList()) {
            if (member instanceof ServerPlayer player) {
                if (!runtime.portals().canDepart(player, portal)) {
                    return false;
                }
            } else if (!portal.isOutgoingTraversalsEnabled()) {
                return false;
            }
        }
        return true;
    }

    private boolean valid(Active active) {
        return !closed && traversals.get(active.entity.getUUID()) == active && registrations.get(active.portal.getId()) == active.registration
            && active.entity.level() == active.level && eligible(active.entity, active.portal)
            && active.entity.position().distanceToSqr(active.position) <= RtpTraversalHoldPolicy.ARRIVAL_DRIFT_SQUARED;
    }

    private void arrive(Active active, RtpService.LoadedCandidate loaded, Throwable failure) {
        try {
            if (failure != null) {
                LOGGER.error("Could not prepare random destination for portal {}", active.portal.getId(), failure);
            }
            String rejection = failure != null ? "destination load failed" : loaded == null ? "destination not loaded"
                : !valid(active) ? "traveler moved before arrival" : !safety.validate(loaded.validationRequest()).join().safe() ? "destination unsafe" : null;
            if (rejection != null) {
                cancel(active, TraversalRefundReason.DESTINATION_UNAVAILABLE, true, rejection);
                return;
            }
            RtpDestination destination = active.preparation.claim().destination();
            ServerLevel level = candidates.level(destination.worldKey());
            RtpValidationRequest.EntityEnvelope envelope = envelope(active.entity);
            if (!envelope.equals(loaded.validationRequest().entityEnvelope()) || level == null) {
                cancel(active, TraversalRefundReason.DESTINATION_UNAVAILABLE, true, level == null ? "destination world unavailable" : "traveler size changed");
                return;
            }
            Vec3 target = new Vec3(destination.blockX() + 0.5D - (envelope.minimumXOffset() + envelope.maximumXOffset()) / 2D,
                destination.feetY() - envelope.minimumYOffset(), destination.blockZ() + 0.5D - (envelope.minimumZOffset() + envelope.maximumZOffset()) / 2D);
            Frame frame = RtpProjectionGeometry.targetFrameFor(active.portal.getFrame());
            TransitConfig config = runtime.configuration().settings().getTransit();
            MomentumPolicy momentum = MomentumPolicy.decode((String) active.portal.setting("transit.momentum"));
            if (momentum == null) {
                momentum = MomentumPolicy.of(MomentumPolicy.Mode.parse(config.momentumDefault, MomentumPolicy.Mode.PRESERVE));
            }
            OrientationPolicy orientation = OrientationPolicy.parse((String) active.portal.setting("transit.orientation"),
                OrientationPolicy.parse(config.orientationDefault, OrientationPolicy.FRAME));
            TravelMessage.ArrivalRules rules = TravelMessage.ArrivalRules.of(orientation, momentum, config.gravityFlipEnabled, config.momentumMaxSpeed,
                ScaleRule.OFF);
            Pose landed = PoseTransform.arrive(MinecraftArrivalPose.departure(active.entity, active.crossing), active.crossing,
                Similarity.of(active.crossing.toward(frame, new Vec3d(target.x, target.y, target.z)), 1.0D), frame, rules.orientation(),
                rules.gravityFlip(), rules.momentum(), rules.momentum().maxSpeed());
            Vec3d velocity = landed.velocity();
            Angles.Look look = new Angles.Look(landed.yaw(), landed.pitch());
            for (Entity member : active.entity.getSelfAndPassengers().toList()) {
                if (member instanceof ServerPlayer player) {
                    MinecraftTravelCosts.Admission cost = runtime.costs().open(new MinecraftTraversalContext(UUID.randomUUID(), TraversalKind.RANDOM_TELEPORT,
                        player, active.portal.getId(), active.portal.getName(), MinecraftTraversalContext.Location.of(player),
                        Optional.of(new MinecraftTraversalContext.Destination("", null,
                            new MinecraftTraversalContext.Location(level, target, look.yaw(), look.pitch())))));
                    if (!cost.allowed()) {
                        cancel(active, TraversalRefundReason.TRAVERSAL_ABORTED, true, "travel cost refused");
                        return;
                    }
                    active.payments.add(cost);
                    active.preSend.add(runtime.preSend().preSend(player, level, (int) Math.floor(target.x), (int) Math.floor(target.z)));
                }
            }
            String refusal = !valid(active) ? "traveler moved before dispatch" : !runtime.rules().reserve(active.entity, active.portal) ? "rules refused departure"
                : !service.markTraversalDispatched(active.preparation).join() ? "dispatch superseded" : null;
            if (refusal != null) {
                cancel(active, TraversalRefundReason.TRAVERSAL_ABORTED, true, refusal);
                return;
            }
            MinecraftTraversalCues.threshold(runtime, active.portal, active.crossing.point(), active.entity);
            Entity arrived = active.entity.teleport(new TeleportTransition(level, target, vector(velocity), look.yaw(), look.pitch(),
                TeleportTransition.PLACE_PORTAL_TICKET));
            if (arrived == null) {
                cancel(active, TraversalRefundReason.TELEPORT_FAILED, true, "teleport rejected");
                return;
            }
            MinecraftArrivalPose.apply(arrived, landed);
            active.finished = true;
            traversals.remove(active.entity.getUUID(), active);
            active.payments.forEach(MinecraftTravelCosts.Admission::commit);
            for (ChunkPreSendTicket<ServerLevel, ServerPlayer> ticket : active.preSend) {
                MinecraftTransit.arrived(runtime, active.portal, ticket.player(), active.level != level, ticket);
            }
            runtime.rules().arrived(arrived, active.portal);
            observe(service.completeTraversal(active.preparation, true), "complete", active.portal.getId());
            for (Entity member : arrived.getSelfAndPassengers().toList()) {
                MinecraftTraversalCues.arrival(runtime, active.portal, member, false);
                runtime.portals().recordArrival(member, active.portal);
                if (member instanceof ServerPlayer player) {
                    runtime.atlas().departed(player, active.portal);
                }
            }
        } catch (RuntimeException error) {
            LOGGER.error("Random traversal failed for portal {}", active.portal.getId(), error);
            cancel(active, TraversalRefundReason.TELEPORT_FAILED, true, "traversal failed");
        } finally {
            if (loaded != null) {
                loaded.retention().close();
            }
        }
    }

    private void hold(Active active, long now) {
        Entity entity = active.entity;
        if (!valid(active)) {
            cancel(active, TraversalRefundReason.TRAVERSAL_ABORTED, false, "traveler invalid during hold");
            return;
        }
        double drift = entity.position().distanceToSqr(active.position);
        RtpTraversalHoldPolicy.Decision decision = RtpTraversalHoldPolicy.decide(false, true, entity.level() == active.level,
            active.crossing.sourceSideDistance(geometry(entity.position())), drift, now - active.started);
        switch (decision) {
            case STOP_ARRIVED -> cancel(active, TraversalRefundReason.TRAVERSAL_ABORTED, false, "traveler left the source area");
            case BOUNCE_FAILED -> cancel(active, TraversalRefundReason.TIMED_OUT, true, "traversal no longer in flight");
            case BOUNCE_TIMEOUT -> cancel(active, TraversalRefundReason.TIMED_OUT, true, "hold timed out");
            case CANCEL_RETREAT -> cancel(active, TraversalRefundReason.TRAVELER_RETREATED, false, "traveler retreated");
            case HOLD_FREE -> { }
            case HOLD_PIN -> {
                entity.setDeltaMovement(Vec3.ZERO);
                entity.fallDistance = 0;
                if (drift > RtpTraversalHoldPolicy.LEASH_DRIFT_SQUARED) {
                    entity.teleport(new TeleportTransition(active.level, active.position, Vec3.ZERO, entity.getYRot(), entity.getXRot(),
                        TeleportTransition.DO_NOTHING));
                }
            }
        }
    }

    private void cancel(Active active, TraversalRefundReason reason, boolean bounce, String cause) {
        if (active.finished) {
            return;
        }
        active.finished = true;
        LOGGER.debug("RTP traversal of {} through {} cancelled: {} ({})", active.entity.getUUID(), active.portal.getId(), cause, reason);
        traversals.remove(active.entity.getUUID(), active);
        runtime.rules().failed(active.entity);
        if (active.preparation != null) {
            observe(service.completeTraversal(active.preparation, false), "cancel", active.portal.getId());
        }
        for (int index = active.payments.size() - 1; index >= 0; index--) {
            active.payments.get(index).refund(reason);
        }
        active.payments.clear();
        for (ChunkPreSendTicket<ServerLevel, ServerPlayer> ticket : active.preSend) {
            runtime.preSend().rollback(ticket);
        }
        active.preSend.clear();
        if (bounce && active.entity.isAlive() && active.entity.level() == active.level
            && active.entity.position().distanceToSqr(active.position) < RtpTraversalHoldPolicy.RETREAT_CANCEL_DRIFT_SQUARED) {
            active.entity.teleport(new TeleportTransition(active.level, vector(active.crossing.rejectionPoint()), Vec3.ZERO,
                active.entity.getYRot(), active.entity.getXRot(), TeleportTransition.DO_NOTHING));
            runtime.portals().recordArrival(active.entity, active.portal);
        }
    }

    private static RtpWorld world(ServerLevel level) {
        if (level == null) {
            return null;
        }
        String key = level.dimension().identifier().toString();
        return new RtpWorld(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), key, level.getMinY(), level.getMaxY() + 1, level.getSeaLevel());
    }

    private static RtpValidationRequest.EntityEnvelope envelope(Entity entity) {
        AABB box = entity.getBoundingBox();
        Vec3 position = entity.position();
        return new RtpValidationRequest.EntityEnvelope(box.minX - position.x, box.maxX - position.x,
            box.minY - position.y, box.maxY - position.y, box.minZ - position.z, box.maxZ - position.z);
    }

    private static Vec3d geometry(Vec3 vector) {
        return new Vec3d(vector.x, vector.y, vector.z);
    }

    private static Vec3 vector(Vec3d vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    private static void observe(CompletableFuture<?> result, String operation, UUID portal) {
        result.whenComplete((ignored, error) -> {
            if (error != null) {
                LOGGER.error("Could not {} RTP portal {}", operation, portal, error);
            }
        });
    }

    private final class Dispatcher implements RtpService.SourceDispatcher {
        @Override
        public void execute(UUID portal, Runnable command) {
            if (server.isSameThread()) {
                command.run();
            } else {
                server.execute(command);
            }
        }

        @Override
        public void schedule(UUID portal, Runnable command, long delayMillis) {
            if (!runtime.schedule(command, Math.max(1L, (delayMillis + 49L) / 50L))) {
                throw new IllegalStateException("RTP runtime stopped before delayed operation");
            }
        }
    }

    private record Registration(MinecraftPortal portal, Object stored, RtpSettings settings) {
    }

    private record RimState(RtpRimRenderer.Phase phase, long elapsed) {
    }

    private record ViewKey(UUID portal, UUID viewer) {
    }

    private static final class View {
        private long touched;
        private long plateIdentity;
        private RtpProjectionView.ReadyData ready;
        private MinecraftPortal destination;
    }

    private static final class Active {
        private final Entity entity;
        private final MinecraftPortal portal;
        private final PlaneCrossing crossing;
        private final Registration registration;
        private final ServerLevel level;
        private final Vec3 position;
        private final long started = System.currentTimeMillis();
        private final List<MinecraftTravelCosts.Admission> payments = new ArrayList<>();
        private final List<ChunkPreSendTicket<ServerLevel, ServerPlayer>> preSend = new ArrayList<>();
        private RtpService.TraversalPreparation preparation;
        private boolean finished;

        private Active(Entity entity, MinecraftPortal portal, PlaneCrossing crossing, Registration registration) {
            this.entity = entity;
            this.portal = portal;
            this.crossing = crossing;
            this.registration = registration;
            level = (ServerLevel) entity.level();
            position = entity.position();
        }
    }
}
