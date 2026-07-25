package art.arcane.wormholes.chunk.presend;

import art.arcane.wormholes.service.WormholesTelemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class ChunkPreSendService<W, P> {
    private static final int MAX_ROLLBACK_STALLS = 3;

    private final ChunkPreSendPlatform<W, P> platform;
    private final Supplier<ChunkPreSendOptions> options;

    public ChunkPreSendService(ChunkPreSendPlatform<W, P> platform) {
        this(platform, ChunkPreSendSettings::active);
    }

    public ChunkPreSendService(ChunkPreSendPlatform<W, P> platform, Supplier<ChunkPreSendOptions> options) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.options = Objects.requireNonNull(options, "options");
    }

    public ChunkPreSendTicket<W, P> preSend(
        P player,
        W destinationWorld,
        int destinationBlockX,
        int destinationBlockZ
    ) {
        P target = Objects.requireNonNull(player, "player");
        ChunkPreSendOptions active = current();
        if (!active.enabled()) {
            return reject(ChunkPreSendOutcome.SKIPPED_DISABLED, target);
        }
        boolean supported = platform.supported();
        boolean online = supported && platform.online(target);
        W sourceWorld = online ? platform.world(target) : null;
        boolean playerUsable = online && sourceWorld != null;
        boolean destinationLocal = destinationWorld != null;
        int destinationCenterX = destinationBlockX >> 4;
        int destinationCenterZ = destinationBlockZ >> 4;
        int clientViewDistance = playerUsable ? platform.clientViewDistance(target) : 0;
        boolean inspectable = playerUsable && destinationLocal && active.usable();
        int burstRadius = inspectable
            ? ChunkPreSendPlanner.effectiveRadius(active.radiusChunks(), clientViewDistance)
            : 0;
        boolean destinationRegionOwned = inspectable && platform.regionOwned(
            destinationWorld,
            destinationCenterX - burstRadius,
            destinationCenterZ - burstRadius,
            destinationCenterX + burstRadius,
            destinationCenterZ + burstRadius
        );
        boolean destinationLoaded = destinationRegionOwned
            && platform.chunkLoaded(destinationWorld, destinationCenterX, destinationCenterZ);
        ChunkPreSendRequest request = new ChunkPreSendRequest(
            supported,
            playerUsable,
            destinationLocal,
            destinationLoaded,
            destinationRegionOwned,
            destinationLocal && Objects.equals(destinationWorld, sourceWorld),
            playerUsable ? platform.chunkX(target) : 0,
            playerUsable ? platform.chunkZ(target) : 0,
            destinationCenterX,
            destinationCenterZ,
            clientViewDistance,
            active
        );
        ChunkPreSendPlan plan = ChunkPreSendPlanner.plan(request);
        if (!plan.outcome().delivered()) {
            return reject(plan.outcome(), target);
        }
        return deliver(target, sourceWorld, destinationWorld, request, plan, active);
    }

    public ChunkPreSendRollbackOutcome rollback(ChunkPreSendTicket<W, P> ticket) {
        ChunkPreSendTicket<W, P> active = Objects.requireNonNull(ticket, "ticket");
        if (!active.rollbackRequired()) {
            return report(ChunkPreSendRollbackOutcome.NOT_NEEDED);
        }
        if (!active.claim()) {
            return report(ChunkPreSendRollbackOutcome.ALREADY_CONSUMED);
        }
        P player = active.player();
        if (!platform.online(player)) {
            return report(ChunkPreSendRollbackOutcome.PLAYER_OFFLINE);
        }
        ChunkPreSendRollback plan = active.rollback();
        if (!platform.announceViewCenter(player, plan.sourceCenterX(), plan.sourceCenterZ())) {
            return report(ChunkPreSendRollbackOutcome.VIEW_CENTER_FAILED);
        }
        return drain(player, active.sourceWorld(), plan.resend(), 0, 0);
    }

    private ChunkPreSendTicket<W, P> deliver(
        P player,
        W sourceWorld,
        W destinationWorld,
        ChunkPreSendRequest request,
        ChunkPreSendPlan plan,
        ChunkPreSendOptions active
    ) {
        long deadline = platform.nanoTime() + active.budgetNanos();
        List<ChunkCoordinate> deliverable = new ArrayList<>(plan.size());
        int unloaded = 0;
        int inspected = 0;
        boolean starved = false;
        for (ChunkCoordinate coordinate : plan.chunks()) {
            if (inspected > 0 && platform.nanoTime() >= deadline) {
                starved = true;
                break;
            }
            inspected++;
            if (!platform.chunkLoaded(destinationWorld, coordinate.x(), coordinate.z())) {
                unloaded++;
                continue;
            }
            if (request.sameWorld() && platform.alreadySent(player, coordinate.x(), coordinate.z())) {
                continue;
            }
            deliverable.add(coordinate);
        }
        if (deliverable.isEmpty()) {
            return reject(
                unloaded > 0 ? ChunkPreSendOutcome.SKIPPED_NO_CHUNKS : ChunkPreSendOutcome.SKIPPED_ALREADY_PRESENT,
                player
            );
        }
        if (!platform.announceViewCenter(player, plan.centerX(), plan.centerZ())) {
            return reject(ChunkPreSendOutcome.FAILED_VIEW_CENTER, player);
        }
        List<ChunkCoordinate> sent = new ArrayList<>(deliverable.size());
        boolean truncated = plan.truncated() || starved || unloaded > 0;
        for (ChunkCoordinate coordinate : deliverable) {
            if (!sent.isEmpty() && platform.nanoTime() >= deadline) {
                truncated = true;
                break;
            }
            if (!platform.sendChunk(player, destinationWorld, coordinate.x(), coordinate.z())) {
                truncated = true;
                break;
            }
            sent.add(coordinate);
        }
        ClientChunkWindow sourceWindow = ClientChunkWindow.around(
            request.sourceCenterX(),
            request.sourceCenterZ(),
            request.clientViewDistance()
        );
        ChunkPreSendRollback rollback = ChunkPreSendRollback.of(sourceWindow, sent, request.sameWorld());
        ChunkPreSendOutcome outcome = truncated
            ? ChunkPreSendOutcome.PRE_SENT_PARTIAL
            : ChunkPreSendOutcome.PRE_SENT;
        count(outcome.telemetryReason());
        return new ChunkPreSendTicket<>(outcome, player, sourceWorld, rollback, sent.size(), true);
    }

    private ChunkPreSendRollbackOutcome drain(
        P player,
        W world,
        List<ChunkCoordinate> pending,
        int from,
        int stalls
    ) {
        if (!platform.online(player)) {
            return report(ChunkPreSendRollbackOutcome.PLAYER_OFFLINE);
        }
        ChunkPreSendOptions active = current();
        int slice = Math.max(1, active.maxChunks());
        long deadline = platform.nanoTime() + Math.max(1L, active.budgetNanos());
        int cursor = from;
        int sent = 0;
        while (cursor < pending.size() && sent < slice) {
            if (sent > 0 && platform.nanoTime() >= deadline) {
                break;
            }
            ChunkCoordinate coordinate = pending.get(cursor);
            if (!platform.regionOwned(world, coordinate.x(), coordinate.z(), coordinate.x(), coordinate.z())) {
                break;
            }
            cursor++;
            if (!platform.chunkLoaded(world, coordinate.x(), coordinate.z())) {
                continue;
            }
            if (!platform.sendChunk(player, world, coordinate.x(), coordinate.z())) {
                return report(ChunkPreSendRollbackOutcome.INCOMPLETE);
            }
            sent++;
        }
        if (cursor >= pending.size()) {
            return report(ChunkPreSendRollbackOutcome.RESTORED);
        }
        int nextStalls = sent > 0 ? 0 : stalls + 1;
        if (nextStalls > MAX_ROLLBACK_STALLS) {
            return report(ChunkPreSendRollbackOutcome.REGION_UNREACHABLE);
        }
        int resume = cursor;
        ChunkCoordinate next = pending.get(resume);
        boolean scheduled = platform.scheduleForRegion(
            world,
            next.x(),
            next.z(),
            () -> drain(player, world, pending, resume, nextStalls),
            1L
        );
        if (!scheduled) {
            return report(ChunkPreSendRollbackOutcome.SCHEDULE_REJECTED);
        }
        return report(ChunkPreSendRollbackOutcome.CONTINUED);
    }

    private ChunkPreSendOptions current() {
        ChunkPreSendOptions active = options.get();
        return active == null ? ChunkPreSendOptions.disabled() : active;
    }

    private ChunkPreSendTicket<W, P> reject(ChunkPreSendOutcome outcome, P player) {
        count(outcome.telemetryReason());
        return ChunkPreSendTicket.rejected(outcome, player);
    }

    private ChunkPreSendRollbackOutcome report(ChunkPreSendRollbackOutcome outcome) {
        count(outcome.telemetryReason());
        return outcome;
    }

    private void count(String reason) {
        if (reason != null) {
            WormholesTelemetry.countFailure(reason);
        }
    }
}
