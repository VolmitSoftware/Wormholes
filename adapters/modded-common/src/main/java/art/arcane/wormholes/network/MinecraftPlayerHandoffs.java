package art.arcane.wormholes.network;

import art.arcane.wormholes.api.traversal.TraversalKind;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.chunk.presend.ChunkPreSendTicket;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.modded.MinecraftMenuText;
import art.arcane.wormholes.modded.MinecraftWormholesApi;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftProxyPayload;
import art.arcane.wormholes.modded.MinecraftTravelCosts;
import art.arcane.wormholes.modded.MinecraftTraversalCues;
import art.arcane.wormholes.modded.MinecraftTransit;
import art.arcane.wormholes.modded.MinecraftTraversalContext;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.mixin.ServerConnectionAccess;
import art.arcane.wormholes.portal.PortalCrossing;
import art.arcane.wormholes.portal.DepartureHoldPolicy;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.MomentumTransform;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.OrientationTransform;
import net.minecraft.commands.Commands;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
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

public final class MinecraftPlayerHandoffs implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final long ARRIVAL_TTL = 60_000L;
    private final WormholesModRuntime runtime;
    private final NetworkManager network;
    private final MinecraftGatewayPolicies policies;
    private final PlayerHandoffAdmission admissions = new PlayerHandoffAdmission();
    private final PlayerHandoffRateLimiter rateLimiter = new PlayerHandoffRateLimiter();
    private final PlayerHandoffCompletion completions = new PlayerHandoffCompletion();
    private final TraversalTransferLocks locks = new TraversalTransferLocks();
    private final Map<UUID, Departure> departures = new HashMap<>();
    private final Map<UUID, Preparation> preparations = new HashMap<>();
    private boolean closed;

    public MinecraftPlayerHandoffs(WormholesModRuntime runtime, NetworkManager network) {
        this.runtime = runtime;
        this.network = network;
        policies = new MinecraftGatewayPolicies(runtime, network, this);
    }

    public boolean begin(ServerPlayer player, String peerName, MinecraftPortal source, PortalCrossing crossing, UUID destinationId) {
        runtime.requireServerThread();
        if (!closed && source != null && !runtime.network().entityTransfers().convoyPending(player.getUUID())
            && policies.begin(player, source, crossing)) {
            return true;
        }
        return beginResolved(player, peerName, source, crossing, destinationId);
    }

    boolean beginResolved(ServerPlayer player, String peerName, MinecraftPortal source, PortalCrossing crossing, UUID destinationId) {
        runtime.requireServerThread();
        if (closed || player.hasDisconnected() || player.isPassenger() || !player.getPassengers().isEmpty()
            || source != null && (!source.isOpen() || !runtime.portals().canDepart(player, source))) {
            return false;
        }
        if (source != null && !runtime.network().entityTransfers().convoyPending(player.getUUID())
            && !runtime.rules().depart(player, source, () -> beginResolved(player, peerName, source, crossing, destinationId))) {
            return false;
        }
        long now = System.currentTimeMillis();
        NetworkConfig config = network.activeConfig();
        NetworkConfig.PeerEntry peer = network.getPeer(peerName);
        String mode = config.effectiveTransferMode(peerName, config.transferMode);
        PlayerHandoffRateLimiter.Decision rate = rateLimiter.acquire(player.getUUID(), now, rateLimit());
        TraversalAdmissionPolicy.HandoffRejection denial = TraversalAdmissionPolicy.outboundHandoffRejection(peerName, peer,
            network.isPeerReady(peerName), mode, rate.allowed() ? 0L : rate.retryAfterMillis(), locks.remaining(player.getUUID(), now));
        if (denial != null) {
            notice(player, denial.detail());
            return false;
        }
        PlayerTransferMethod method = PlayerTransferMethod.resolve(peer, mode);
        SocketAddress address = ((ServerConnectionAccess) player.connection).wormholesConnection().getRemoteAddress();
        GameEndpoint endpoint = method == PlayerTransferMethod.DIRECT
            ? network.playerEndpoint(peerName, address instanceof InetSocketAddress internet ? internet : null) : null;
        if (method == PlayerTransferMethod.DIRECT && endpoint == null) {
            notice(player, "no destination game address is available for your network");
            return false;
        }
        UUID id = UUID.randomUUID();
        long deadline = now + config.handoffTimeoutMs + (method == PlayerTransferMethod.DIRECT
            ? NetworkManager.PLAYER_ENDPOINT_TIMEOUT_MILLIS : 0L);
        Departure departure = new Departure(player, peerName, source, destinationId, crossing, method, endpoint,
            player.level(), player.position(), deadline);
        departures.put(id, departure);
        locks.lockTransfer(player.getUUID(), id, deadline);
        WireMessage.HandoffRequest request = new WireMessage.HandoffRequest(id, player.getUUID(), player.getGameProfile().name(),
            departure.destinationId(), method == PlayerTransferMethod.DIRECT, runtime.server().usesAuthentication(),
            administrator(player), crossing == null ? null : WireTraversive.fromCrossing(crossing));
        if (method == PlayerTransferMethod.PROXY) {
            queue(id, departure, request);
        } else {
            MinecraftServer server = runtime.server();
            network.validatePlayerEndpoint(peerName, endpoint).whenComplete((validation, error) -> server.execute(() -> {
                if (closed || departures.get(id) != departure) {
                    return;
                }
                if (error != null) {
                    LOGGER.error("Could not verify Wormholes transfer endpoint for {}", peerName, error);
                }
                if (error != null || validation == null || !validation.accepted()) {
                    reject(id, departure, validation == null ? "destination endpoint validation failed" : validation.detail());
                } else {
                    queue(id, departure, request);
                }
            }));
        }
        return true;
    }

    public boolean locked(UUID playerId) {
        return policies.locked(playerId) || locks.isLocked(playerId, System.currentTimeMillis());
    }

    public boolean hasAdmission(UUID playerId) {
        return admissions.hasAdmission(playerId, System.currentTimeMillis());
    }

    public int activeReservations() {
        return admissions.activeReservations(System.currentTimeMillis());
    }

    public boolean reservedCapacityFull(NameAndId profile) {
        PlayerList players = runtime.server().getPlayerList();
        if (hasAdmission(profile.id()) || players.isOp(profile) || players.canBypassPlayerLimit(profile)) {
            return false;
        }
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : players.getPlayers()) {
            online.add(player.getUUID());
        }
        int reserved = admissions.reservedOfflinePlayers(online, System.currentTimeMillis());
        return players.getMaxPlayers() > 0 && online.size() + reserved >= players.getMaxPlayers();
    }

    public void receive(String peer, WireMessage message) {
        runtime.requireServerThread();
        if (closed) {
            return;
        }
        switch (message) {
            case WireMessage.HandoffRequest request -> request(peer, request);
            case WireMessage.HandoffAck ack -> acknowledge(peer, ack);
            case WireMessage.HandoffDeny deny -> {
                Departure departure = departures.get(deny.transferId());
                if (departure != null && departure.peer().equals(peer)) {
                    rateLimiter.penalize(departure.player().getUUID(), System.currentTimeMillis(), deny.retryAfterMillis());
                    reject(deny.transferId(), departure, deny.reason());
                }
            }
            case WireMessage.HandoffCancel cancel -> {
                if (admissions.cancel(new PlayerHandoffAdmission.Cancellation(peer, cancel.transferId(), cancel.playerId(),
                    System.currentTimeMillis(), rateLimit(), ARRIVAL_TTL))) {
                    releasePreparation(cancel.transferId());
                }
            }
            case WireMessage.HandoffResult result -> {
                if (completions.acknowledge(peer, result) != null) {
                    runtime.network().entityTransfers().receive(peer, result);
                    locks.unlockTransfer(result.playerId(), result.transferId());
                    if (!result.arrived()) {
                        LOGGER.warn("Wormholes arrival failed for {} on {}: {}", result.playerId(), peer, result.detail());
                    }
                }
            }
            case WireMessage.HandoffStatus status -> {
                WireMessage.HandoffResult result = completions.receipt(peer, status, System.currentTimeMillis());
                if (result != null) {
                    network.send(peer, result);
                }
            }
            default -> {
            }
        }
    }

    public void tick() {
        policies.tick();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Departure> entry : List.copyOf(departures.entrySet())) {
            Departure departure = entry.getValue();
            if (departure.deadline() <= now || !validDeparture(departure)) {
                reject(entry.getKey(), departure, departure.deadline() <= now ? "destination admission timed out" : "departure interrupted");
            } else if (departure.source() != null) {
                hold(departure);
            }
        }
        for (Map.Entry<UUID, Preparation> entry : List.copyOf(preparations.entrySet())) {
            if (entry.getValue().reservation().expiresAtMillis() <= now) {
                releasePreparation(entry.getKey());
            }
        }
        PlayerHandoffCompletion.Maintenance maintenance = completions.maintain(now);
        for (PlayerHandoffCompletion.Attempt attempt : maintenance.queries()) {
            network.send(attempt.peerName(), new WireMessage.HandoffStatus(attempt.transferId(), attempt.playerId()));
        }
        for (PlayerHandoffCompletion.Attempt attempt : maintenance.expired()) {
            locks.unlockTransfer(attempt.playerId(), attempt.transferId());
            LOGGER.warn("Wormholes arrival was not confirmed for {} on {}", attempt.playerId(), attempt.peerName());
        }
        admissions.prune(now);
        locks.prune(now);
    }

    public void joined(ServerPlayer player) {
        runtime.requireServerThread();
        PlayerHandoffAdmission.Reservation arrival = admissions.claimArrival(player.getUUID(), System.currentTimeMillis());
        if (arrival != null && !runtime.schedule(() -> place(player, arrival), 1L)) {
            finish(arrival, false, "destination stopped before placement");
        }
    }

    public void disconnected(ServerPlayer player) {
        policies.disconnected(player);
        for (Map.Entry<UUID, Departure> entry : List.copyOf(departures.entrySet())) {
            if (entry.getValue().player() == player) {
                reject(entry.getKey(), entry.getValue(), "traveler disconnected before departure");
            }
        }
    }

    @Override
    public void close() {
        policies.close();
        closed = true;
        for (Map.Entry<UUID, Departure> entry : List.copyOf(departures.entrySet())) {
            reject(entry.getKey(), entry.getValue(), "source server stopped");
        }
        for (Preparation preparation : preparations.values()) {
            closeLeases(preparation.leases());
        }
        preparations.clear();
        admissions.clear();
        completions.clear();
        rateLimiter.clear();
        locks.clear();
    }

    private void queue(UUID id, Departure departure, WireMessage.HandoffRequest request) {
        if (!validDeparture(departure) || !network.isPeerReady(departure.peer()) || !network.send(departure.peer(), request)) {
            reject(id, departure, "destination could not accept the handoff request");
        }
    }

    private void request(String peer, WireMessage.HandoffRequest request) {
        long now = System.currentTimeMillis();
        MinecraftPortal exit = request.destPortalId() == null ? null : runtime.portals().get(request.destPortalId());
        PlayerHandoffAdmission.Request admission = new PlayerHandoffAdmission.Request(request.transferId(), request.playerId(),
            request.playerName(), peer, request.destPortalId(), request.directTransfer(), request.accessBypass(), request.traversive());
        PlayerHandoffAdmission.Decision decision = admissions.decide(new PlayerHandoffAdmission.Attempt(admission,
            destinationDenial(request, exit, now), now, ARRIVAL_TTL, rateLimit()));
        if (!decision.accepted()) {
            network.send(peer, new WireMessage.HandoffDeny(request.transferId(), decision.reason(),
                TraversalAdmissionPolicy.denialRetryMillis(decision.reason(), decision.retryAfterMillis())));
            return;
        }
        if (!decision.fresh()) {
            Preparation previous = preparations.get(request.transferId());
            if (previous == null || previous.ready().isDone() && Boolean.TRUE.equals(previous.ready().getNow(false))) {
                queueAcknowledgement(admission);
            }
            return;
        }
        if (exit == null) {
            queueAcknowledgement(admission);
            return;
        }
        try {
            prepare(decision.reservation(), exit);
        } catch (RuntimeException error) {
            LOGGER.error("Could not prepare Wormholes arrival {}", request.transferId(), error);
            preparationFailed(admission);
        }
    }

    private void prepare(PlayerHandoffAdmission.Reservation reservation, MinecraftPortal exit) {
        PlayerHandoffAdmission.Request admission = reservation.request();
        ServerLevel level = runtime.portals().resolveLevel(exit);
        GeometryVector target = admission.traversive().crossing().outPoint(exit.getFrame(), exit.getOrigin());
        if (!finite(target) || target.y() < level.getMinY() || target.y() >= level.getMaxY()
            || Math.abs(target.x()) > 29_999_984 || Math.abs(target.z()) > 29_999_984) {
            throw new IllegalArgumentException("Arrival position is outside destination bounds");
        }
        List<ChunkLease> leases = new ArrayList<>(9);
        try {
            UUID world = UUID.nameUUIDFromBytes(exit.getWorldKey().getBytes(StandardCharsets.UTF_8));
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    leases.add(runtime.leases().retain(level, world, (target.getBlockX() >> 4) + x, (target.getBlockZ() >> 4) + z));
                }
            }
        } catch (RuntimeException error) {
            closeLeases(leases);
            throw error;
        }
        CompletableFuture<Boolean> ready = CompletableFuture.allOf(leases.stream().map(ChunkLease::ready)
            .toArray(CompletableFuture<?>[]::new)).thenApply(ignored -> leases.stream().allMatch(lease -> Boolean.TRUE.equals(lease.ready().getNow(false))));
        Preparation preparation = new Preparation(reservation, leases, ready);
        preparations.put(admission.transferId(), preparation);
        MinecraftServer server = runtime.server();
        ready.whenComplete((success, error) -> server.execute(() -> {
            if (closed || preparations.get(admission.transferId()) != preparation) {
                return;
            }
            if (error != null) {
                LOGGER.error("Could not prepare Wormholes arrival {}", admission.transferId(), error);
            }
            if (error != null || !Boolean.TRUE.equals(success)) {
                preparationFailed(admission);
            } else {
                queueAcknowledgement(admission);
            }
        }));
    }

    private void preparationFailed(PlayerHandoffAdmission.Request admission) {
        admissions.release(admission, System.currentTimeMillis());
        releasePreparation(admission.transferId());
        network.send(admission.peerName(), new WireMessage.HandoffDeny(admission.transferId(), "destination terrain preparation failed", rateLimit()));
    }

    private String destinationDenial(WireMessage.HandoffRequest request, MinecraftPortal exit, long now) {
        MinecraftServer server = runtime.server();
        if (request.destPortalId() != null && (exit == null || !exit.isOpen() || runtime.portals().resolveLevel(exit) == null)) {
            return "destination portal is unavailable";
        }
        String identity = TraversalAdmissionPolicy.directIdentityDenial(request, server.usesAuthentication());
        if (identity != null) {
            return identity;
        }
        PlayerList players = server.getPlayerList();
        if (players.getPlayer(request.playerId()) != null) {
            return "player already connected";
        }
        NameAndId profile = new NameAndId(request.playerId(), request.playerName());
        boolean operator = players.isOp(profile) || request.accessBypass();
        if (exit != null && !TraversalAdmissionPolicy.acceptsInbound(exit, operator)) {
            return "portal receive disabled";
        }
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : players.getPlayers()) {
            online.add(player.getUUID());
        }
        return TraversalAdmissionPolicy.destinationPlayerDenialReason(new TraversalAdmissionPolicy.DestinationPlayerState(
            request.directTransfer(), network.activeConfig().autoAcceptTransfers || server.acceptsTransfers(),
            players.getBans().isBanned(profile), players.isUsingWhitelist(), players.isWhiteListed(profile), operator,
            online.size() + admissions.reservedOfflinePlayers(online, now), players.getMaxPlayers(), network.drain().isDraining()));
    }

    private void queueAcknowledgement(PlayerHandoffAdmission.Request admission) {
        if (!admissions.queueAcknowledgement(admission, System.currentTimeMillis(),
            () -> network.send(admission.peerName(), new WireMessage.HandoffAck(admission.transferId())))) {
            admissions.release(admission, System.currentTimeMillis());
            releasePreparation(admission.transferId());
        }
    }

    private void acknowledge(String peer, WireMessage.HandoffAck ack) {
        Departure departure = departures.get(ack.transferId());
        if (departure == null || !departure.peer().equals(peer)) {
            return;
        }
        if (!validDeparture(departure) || network.getPeer(peer) == null || !network.isPeerReady(peer)) {
            reject(ack.transferId(), departure, "departure interrupted before destination acknowledgement");
            return;
        }
        ServerPlayer player = departure.player();
        long now = System.currentTimeMillis();
        if (!locks.renewTransfer(player.getUUID(), ack.transferId(), now + ARRIVAL_TTL)) {
            reject(ack.transferId(), departure, "departure reservation expired");
            return;
        }
        MinecraftTravelCosts.Admission cost = departure.source() == null ? null : runtime.costs().open(new MinecraftTraversalContext(
            ack.transferId(), TraversalKind.CROSS_SERVER, player, departure.source().getId(), departure.source().getName(),
            new MinecraftTraversalContext.Location(departure.level(), departure.position(), player.getYRot(), player.getXRot()),
            Optional.of(new MinecraftTraversalContext.Destination(peer, departure.destinationId(), null))));
        if (cost != null && !cost.allowed()) {
            reject(ack.transferId(), departure, "traversal cost was not authorized");
            return;
        }
        if (departure.source() != null && !runtime.rules().reserve(player, departure.source())) {
            if (cost != null) {
                cost.refund(TraversalRefundReason.TRAVERSAL_ABORTED);
            }
            reject(ack.transferId(), departure, "traversal rule cost was not authorized");
            return;
        }
        completions.dispatched(new PlayerHandoffCompletion.Attempt(ack.transferId(), player.getUUID(), peer, now + ARRIVAL_TTL), now);
        try {
            if (departure.source() != null) {
                MinecraftTraversalCues.threshold(runtime, departure.source(), departure.crossing().point(), player);
            }
            if (departure.method() == PlayerTransferMethod.DIRECT) {
                player.connection.send(new ClientboundTransferPacket(departure.endpoint().host(), departure.endpoint().port()));
            } else {
                player.connection.send(new ClientboundCustomPayloadPacket(MinecraftProxyPayload.connect(peer)));
            }
            if (cost != null) {
                cost.commit();
            }
            emit(MinecraftWormholesApi.Kind.HANDOFF_ADMITTED, player.getUUID(), departure.source() == null ? null : departure.source().getId(), peer, "");
            departures.remove(ack.transferId(), departure);
            runtime.network().entityTransfers().playerDispatched(player.getUUID());
            if (departure.source() != null) {
                runtime.rules().dispatched(player, departure.source());
                runtime.atlas().departed(player, departure.source());
            }
        } catch (RuntimeException error) {
            if (cost != null) {
                cost.refund(TraversalRefundReason.TELEPORT_FAILED);
            }
            LOGGER.error("Could not dispatch Wormholes traveler {} to {}", player.getUUID(), peer, error);
            completions.abandon(ack.transferId());
            reject(ack.transferId(), departure, "player transfer could not be dispatched");
        }
    }

    private void place(ServerPlayer player, PlayerHandoffAdmission.Reservation reservation) {
        if (closed || !admissions.isArrivalClaimActive(reservation, System.currentTimeMillis())) {
            return;
        }
        if (player.hasDisconnected() || runtime.server().getPlayerList().getPlayer(player.getUUID()) != player) {
            admissions.releaseArrival(reservation, System.currentTimeMillis());
            return;
        }
        PlayerHandoffAdmission.Request request = reservation.request();
        if (request.exitPortalId() == null) {
            finish(reservation, true, "server join completed");
            return;
        }
        MinecraftPortal exit = runtime.portals().get(request.exitPortalId());
        if (exit == null || !exit.isOpen() || !(request.accessBypass()
            ? TraversalAdmissionPolicy.acceptsInbound(exit, true) : runtime.portals().canArrive(player, exit))) {
            finish(reservation, false, "destination portal refused arrival");
            notice(player, "destination portal refused arrival");
            return;
        }
        if (!runtime.rules().arrivalAllowed(player, exit, false)) {
            finish(reservation, false, "destination traversal rules refused arrival");
            notice(player, "destination traversal rules refused arrival");
            return;
        }
        ServerLevel level = runtime.portals().resolveLevel(exit);
        if (level == null) {
            finish(reservation, false, "destination world is unavailable");
            return;
        }
        ChunkPreSendTicket<ServerLevel, ServerPlayer> ticket = null;
        try {
            PortalCrossing crossing = request.traversive().crossing();
            GeometryVector target = crossing.outPoint(exit.getFrame(), exit.getOrigin());
            TransitConfig config = runtime.configuration().settings().getTransit();
            MomentumPolicy momentum = MomentumPolicy.decode((String) exit.setting("transit.momentum"));
            if (momentum == null) {
                momentum = MomentumPolicy.of(MomentumPolicy.Mode.parse(config.momentumDefault, MomentumPolicy.Mode.PRESERVE));
            }
            GeometryVector velocity = MomentumTransform.apply(crossing.outVelocity(exit.getFrame()), momentum, config.momentumMaxSpeed);
            OrientationTransform.Look look = OrientationTransform.apply(crossing, exit.getFrame(),
                OrientationPolicy.parse((String) exit.setting("transit.orientation"), OrientationPolicy.parse(config.orientationDefault, OrientationPolicy.FRAME)),
                config.gravityFlipEnabled);
            ticket = runtime.preSend().preSend(player, level, target.getBlockX(), target.getBlockZ());
            if (player.teleport(new TeleportTransition(level, vector(target), vector(velocity), look.yaw(), look.pitch(),
                TeleportTransition.PLACE_PORTAL_TICKET)) == null) {
                runtime.preSend().rollback(ticket);
                finish(reservation, false, "destination teleport was rejected");
                return;
            }
            MinecraftTraversalCues.arrival(runtime, exit, player, false);
            MinecraftTransit.arrived(runtime, exit, player, true, ticket, false);
            runtime.portals().recordArrival(player, exit);
            runtime.network().entityTransfers().playerPlaced(player, exit, crossing);
            finish(reservation, true, "portal arrival completed");
        } catch (RuntimeException error) {
            LOGGER.error("Could not place Wormholes traveler {} at {}", player.getUUID(), exit.getId(), error);
            if (ticket != null) {
                runtime.preSend().rollback(ticket);
            }
            finish(reservation, false, "destination placement failed");
            notice(player, "destination placement failed");
        }
    }

    private void finish(PlayerHandoffAdmission.Reservation reservation, boolean arrived, String detail) {
        if (!admissions.completeArrival(reservation, System.currentTimeMillis())) {
            return;
        }
        PlayerHandoffAdmission.Request request = reservation.request();
        emit(arrived ? MinecraftWormholesApi.Kind.HANDOFF_COMPLETED : MinecraftWormholesApi.Kind.HANDOFF_DENIED,
            request.playerId(), request.exitPortalId(), request.peerName(), detail);
        WireMessage.HandoffResult result = completions.record(request.peerName(), new WireMessage.HandoffResult(
            request.transferId(), request.playerId(), arrived, detail), System.currentTimeMillis());
        releasePreparation(request.transferId());
        if (result != null) {
            network.send(request.peerName(), result);
        }
    }

    private boolean validDeparture(Departure departure) {
        ServerPlayer player = departure.player();
        if (player.hasDisconnected() || player.isRemoved() || player.isPassenger() || !player.getPassengers().isEmpty()) {
            return false;
        }
        MinecraftPortal source = departure.source();
        return source == null || runtime.portals().get(source.getId()) == source && source.isOpen()
            && runtime.portals().canDepart(player, source) && (runtime.nexus().perTraveler(source) || MinecraftGatewayPolicies.active(source) || runtime.api() != null && runtime.api().hasResolvers()
                || departure.peer().equals(source.getDestinationServer()) && departure.destinationId().equals(source.getDestinationId()))
            && DepartureHoldPolicy.decide(true, player.level() == departure.level(),
                departure.crossing().sourceSideDistance(geometry(player.position())), player.position().distanceToSqr(departure.position()),
                departure.deadline() - System.currentTimeMillis()) == DepartureHoldPolicy.Decision.HOLD_PIN;
    }

    private void emit(MinecraftWormholesApi.Kind kind, UUID playerId, UUID portalId, String peer, String detail) {
        MinecraftWormholesApi api = runtime.api();
        if (api != null) {
            api.emit(new MinecraftWormholesApi.Event(kind, playerId, portalId, peer, detail, null, null));
        }
    }

    private void hold(Departure departure) {
        ServerPlayer player = departure.player();
        if (departure.source() != null) {
            runtime.rules().retain(player);
        }
        player.setDeltaMovement(Vec3.ZERO);
        if (player.position().distanceToSqr(departure.position()) > DepartureHoldPolicy.LEASH_DRIFT_SQUARED) {
            Vec3 target = departure.position();
            player.connection.teleport(target.x, target.y, target.z, player.getYRot(), player.getXRot());
        }
    }

    private void reject(UUID id, Departure departure, String reason) {
        if (!departures.remove(id, departure)) {
            return;
        }
        emit(MinecraftWormholesApi.Kind.HANDOFF_DENIED, departure.player().getUUID(), departure.source() == null ? null : departure.source().getId(), departure.peer(), reason);
        network.send(departure.peer(), new WireMessage.HandoffCancel(id, departure.player().getUUID()));
        locks.unlockTransfer(departure.player().getUUID(), id);
        rateLimiter.penalize(departure.player().getUUID(), System.currentTimeMillis(), rateLimit());
        ServerPlayer player = departure.player();
        if (departure.source() != null) {
            runtime.rules().failed(player);
        }
        runtime.network().entityTransfers().playerFailed(player.getUUID(), reason);
        if (!player.hasDisconnected()) {
            if (departure.source() != null && player.level() == departure.level()
                && player.position().distanceToSqr(departure.position()) <= DepartureHoldPolicy.FAR_DRIFT_SQUARED
                && (departure.crossing().sourceSideDistance(geometry(player.position())) <= DepartureHoldPolicy.RETREAT_FREE_DISTANCE
                    || player.position().distanceToSqr(departure.position()) <= DepartureHoldPolicy.RETREAT_CANCEL_DRIFT_SQUARED)) {
                GeometryVector target = departure.crossing().rejectionPoint();
                player.connection.teleport(target.x(), target.y(), target.z(), player.getYRot(), player.getXRot());
                double strength = 3.0D * runtime.configuration().settings().getMain().portalPushbackMultiplier;
                player.setDeltaMovement(new Vec3(departure.crossing().frame().getNormal().x() * strength,
                    departure.crossing().frame().getNormal().y() * strength, departure.crossing().frame().getNormal().z() * strength));
                runtime.portals().recordArrival(player, departure.source());
            }
            notice(player, reason);
        }
    }

    private void releasePreparation(UUID id) {
        Preparation preparation = preparations.remove(id);
        if (preparation != null) {
            closeLeases(preparation.leases());
        }
    }

    private long rateLimit() {
        return TraversalAdmissionPolicy.handoffRateLimitMillis(runtime.configuration().settings().getMain().teleportCooldownMillis);
    }

    private static boolean administrator(ServerPlayer player) {
        return Commands.hasPermission(Commands.LEVEL_ADMINS).test(player.createCommandSourceStack());
    }

    private static void notice(ServerPlayer player, String reason) {
        player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.PORTAL_DESTINATION_UNREACHABLE_DETAIL, Map.of("reason", reason)), true);
    }

    private static void closeLeases(List<ChunkLease> leases) {
        for (ChunkLease lease : leases) {
            lease.close();
        }
    }

    private static GeometryVector geometry(Vec3 point) {
        return new GeometryVector(point.x, point.y, point.z);
    }

    private static Vec3 vector(GeometryVector point) {
        return new Vec3(point.x(), point.y(), point.z());
    }

    private static boolean finite(GeometryVector point) {
        return Double.isFinite(point.x()) && Double.isFinite(point.y()) && Double.isFinite(point.z());
    }

    private record Departure(ServerPlayer player, String peer, MinecraftPortal source, UUID destinationId, PortalCrossing crossing,
                             PlayerTransferMethod method, GameEndpoint endpoint, ServerLevel level, Vec3 position, long deadline) {
    }

    private record Preparation(PlayerHandoffAdmission.Reservation reservation, List<ChunkLease> leases,
                               CompletableFuture<Boolean> ready) {
    }
}
