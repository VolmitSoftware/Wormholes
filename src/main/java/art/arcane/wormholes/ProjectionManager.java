package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.rtp.RtpProjectionView;
import art.arcane.wormholes.portal.rtp.RtpRimRenderer;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.network.view.ViewServer;
import art.arcane.wormholes.render.PortalSkinRenderer;
import art.arcane.wormholes.render.ProjectionClaimArbiter;
import art.arcane.wormholes.render.ProjectionClientChunkTracker;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;
import art.arcane.wormholes.render.view.RegionSnapshotWorldViewProvider;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.J;

public class ProjectionManager implements Listener {
    private static final String OBSERVER_FRAME_DROPPED = "PROJECTION_OBSERVER_FRAME_DROPPED";
    private static final String ENTITY_UPDATE_DROPPED = "PROJECTION_ENTITY_UPDATE_DROPPED";
    private static final int TICK_INTERVAL_TICKS = 1;
    private static final AtomicLong DROPPED_OBSERVER_FRAMES = new AtomicLong();
    private static final AtomicLong DROPPED_ENTITY_UPDATES = new AtomicLong();
    private static final ObserverFrameScheduler ENTITY_FRAME_SCHEDULER = (observer, frame, retired) ->
        FoliaScheduler.runEntity(Wormholes.instance, observer, frame, 0L, retired);
    private static final EntityUpdateScheduler ENTITY_UPDATE_SCHEDULER = (observer, update) ->
        FoliaScheduler.runEntity(Wormholes.instance, observer, update);
    private final ProjectionClaimArbiter claimArbiter;
    private final ProjectionClientChunkTracker clientChunkTracker;
    private final ProjectionWorldViewProvider viewProvider;
    private final RtpRimRenderer rtpRimRenderer;
    private final PortalSkinRenderer skinRenderer;
    private final ProjectionInterestCloseQueue closeQueue;
    private final ProjectionInterestSet interestSet;
    private final ProjectionBudgetLedger budgetLedger;
    private final ProjectionInterestFrame observerFrame;
    private final Set<UUID> observerTasksInFlight;
    private final AtomicBoolean shutdownFinalized;
    private final AtomicBoolean shutdownStarted;
    private long tickCount;
    private boolean firstTickLogged;
    private volatile boolean closed;
    private volatile long projectionFrozenUntilMillis;
    private volatile RtpProjectionProvider rtpProjectionProvider;
    private int taskId;

    public ProjectionManager(ProjectionClientChunkTracker clientChunkTracker) {
        this.viewProvider = FoliaScheduler.isFoliaThreading(Bukkit.getServer())
            ? new RegionSnapshotWorldViewProvider(Wormholes.instance)
            : ProjectionWorldViewProvider.live();
        this.clientChunkTracker = clientChunkTracker;
        this.claimArbiter = new ProjectionClaimArbiter(viewProvider, clientChunkTracker);
        this.rtpRimRenderer = new RtpRimRenderer();
        this.skinRenderer = new PortalSkinRenderer(claimArbiter);
        BooleanSupplier alive = () -> !closed;
        this.closeQueue = new ProjectionInterestCloseQueue(alive);
        this.interestSet = new ProjectionInterestSet(claimArbiter, viewProvider, closeQueue, alive);
        this.budgetLedger = new ProjectionBudgetLedger();
        this.observerFrame = new ProjectionInterestFrame(interestSet, budgetLedger, claimArbiter, rtpRimRenderer,
            () -> rtpProjectionProvider, alive);
        this.observerTasksInFlight = ConcurrentHashMap.newKeySet();
        this.shutdownFinalized = new AtomicBoolean();
        this.shutdownStarted = new AtomicBoolean();
        this.tickCount = 0L;
        this.firstTickLogged = false;
        this.closed = false;
        this.projectionFrozenUntilMillis = 0L;
        this.taskId = -1;
        scheduleTick();
    }

    public void setRtpProjectionProvider(RtpProjectionProvider provider) {
        rtpProjectionProvider = provider;
    }

    @EventHandler
    public void on(PlayerQuitEvent e) {
        observerTasksInFlight.remove(e.getPlayer().getUniqueId());
        skinRenderer.discardObserver(e.getPlayer().getUniqueId());
        discardObserverProjectors(e.getPlayer());
    }

    @EventHandler
    public void on(PlayerJoinEvent e) {
        skinRenderer.discardObserver(e.getPlayer().getUniqueId());
        discardObserverProjectors(e.getPlayer());
    }

    @EventHandler
    public void on(PlayerChangedWorldEvent e) {
        skinRenderer.discardObserver(e.getPlayer().getUniqueId());
        discardObserverProjectors(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void on(PlayerAnimationEvent e) {
        EntityAnimationType type = e.getAnimationType() == PlayerAnimationType.OFF_ARM_SWING
                ? EntityAnimationType.SWING_OFF_HAND
                : EntityAnimationType.SWING_MAIN_ARM;
        broadcastProjectedEntityAnimation(e.getPlayer().getUniqueId(), type);
    }

    @EventHandler(ignoreCancelled = true)
    public void on(EntityShootBowEvent e) {
        EntityAnimationType type = e.getHand() == EquipmentSlot.OFF_HAND
                ? EntityAnimationType.SWING_OFF_HAND
                : EntityAnimationType.SWING_MAIN_ARM;
        broadcastProjectedEntityAnimation(e.getEntity().getUniqueId(), type);
    }

    @EventHandler(ignoreCancelled = true)
    public void on(EntityDamageEvent e) {
        Entity entity = e.getEntity();
        broadcastProjectedEntityHurt(entity.getUniqueId(), entity.getLocation().getYaw());
        if (!(e instanceof EntityDamageByEntityEvent)) {
            return;
        }
        EntityDamageByEntityEvent damageByEntityEvent = (EntityDamageByEntityEvent) e;
        Entity source = resolveAttackSource(damageByEntityEvent.getDamager());
        if (source != null && !(source instanceof Player)) {
            broadcastProjectedEntityAnimation(source.getUniqueId(), EntityAnimationType.SWING_MAIN_ARM);
        }
    }

    private void tick() {
        if (closed) {
            return;
        }
        if (projectionsFrozen(projectionFrozenUntilMillis, System.currentTimeMillis())) {
            return;
        }
        tickCount++;
        closeQueue.retryPending();

        if (!firstTickLogged) {
            firstTickLogged = true;
            Wormholes.v("[ProjectionManager] first tick fired");
        }

        if (Wormholes.portalManager == null) {
            if (tickCount % 50L == 1L) {
                Wormholes.w("[ProjectionManager] portalManager is null on tick " + tickCount);
            }
            return;
        }

        List<ILocalPortal> skinnedPortals = collectSkinnedPortals();
        if (!skinnedPortals.isEmpty() || skinRenderer.isActive()) {
            skinRenderer.tick(skinnedPortals, new ArrayList<Player>(Wormholes.instance.getServer().getOnlinePlayers()));
        }

        budgetLedger.beginFrame();
        List<ILocalPortal> active = collectActiveProjectors();
        interestSet.retainPortals(active);
        long frameTick = tickCount;
        if (active.isEmpty() && interestSet.isEmpty() && closeQueue.isEmpty() && claimArbiter.isIdle()) {
            interestSet.pruneGrace(frameTick);
            WormholesTelemetry.setProjectionGauges(0, observerTasksInFlight.size(), countSpoofedEntities());
            budgetLedger.emitDiagnostics(tickCount, active, interestSet);
            return;
        }
        boolean updateBlocks = shouldUpdateBlocks();
        boolean updateEntities = shouldUpdateEntities();
        List<Player> onlinePlayers = new ArrayList<Player>(Wormholes.instance.getServer().getOnlinePlayers());
        List<Player> observerCandidates = budgetLedger.selectObserverCandidates(onlinePlayers, interestSet, frameTick,
            Settings.PROJECTION_MAX_NEW_OBSERVER_SCANS_PER_TICK);
        int totalBudget = updateBlocks ? Math.max(0, Settings.PROJECTION_MAX_PROJECTORS_PER_TICK) : 0;
        int perObserverBudget = Math.max(0, Settings.PROJECTION_MAX_PORTALS_PER_OBSERVER_TICK);
        int[] reservedBudgets = fairBudgetAllocations(observerCandidates.size(), totalBudget, perObserverBudget, frameTick);
        int reservedTotal = 0;
        for (int reserved : reservedBudgets) {
            reservedTotal += reserved;
        }
        AtomicInteger remainingProjectors = new AtomicInteger(Math.max(0, totalBudget - reservedTotal));
        int rotationStart = observerCandidates.isEmpty() ? 0 : (int) Math.floorMod(frameTick, observerCandidates.size());
        for (int offset = 0; offset < observerCandidates.size(); offset++) {
            int index = (rotationStart + offset) % observerCandidates.size();
            Player observer = observerCandidates.get(index);
            UUID observerId = observer.getUniqueId();
            int reservedBudget = reservedBudgets[index];
            dispatchObserverFrame(observerTasksInFlight, observer, observerId, remainingProjectors, reservedBudget, () -> {
                try {
                    if (closed) {
                        remainingProjectors.addAndGet(reservedBudget);
                        return;
                    }
                    observerFrame.project(observer, active, remainingProjectors, reservedBudget,
                        updateBlocks, updateEntities, frameTick);
                } finally {
                    observerTasksInFlight.remove(observerId);
                }
            }, ENTITY_FRAME_SCHEDULER);
        }
        interestSet.pruneGrace(frameTick);
        WormholesTelemetry.setProjectionGauges(active.size(), observerTasksInFlight.size(), countSpoofedEntities());
        budgetLedger.emitDiagnostics(tickCount, active, interestSet);
    }

    static boolean dispatchObserverFrame(Set<UUID> inFlight,
                                         Player observer,
                                         UUID observerId,
                                         AtomicInteger remainingProjectors,
                                         int reservedBudget,
                                         Runnable frame,
                                         ObserverFrameScheduler scheduler) {
        if (!inFlight.add(observerId)) {
            remainingProjectors.addAndGet(reservedBudget);
            return false;
        }
        boolean scheduled = scheduler.schedule(observer, frame,
            () -> releaseObserverFrame(inFlight, observerId, remainingProjectors, reservedBudget));
        if (!scheduled) {
            releaseObserverFrame(inFlight, observerId, remainingProjectors, reservedBudget);
        }
        return scheduled;
    }

    static boolean releaseObserverFrame(Set<UUID> inFlight,
                                        UUID observerId,
                                        AtomicInteger remainingProjectors,
                                        int reservedBudget) {
        if (!inFlight.remove(observerId)) {
            return false;
        }
        remainingProjectors.addAndGet(reservedBudget);
        Wormholes.v("[ProjectionManager] observer frame dropped observer=" + observerId
            + " total=" + DROPPED_OBSERVER_FRAMES.incrementAndGet());
        WormholesTelemetry.countFailure(OBSERVER_FRAME_DROPPED);
        return true;
    }

    static boolean dispatchProjectedEntityUpdate(Player observer,
                                                 UUID entityId,
                                                 String kind,
                                                 Runnable update,
                                                 EntityUpdateScheduler scheduler) {
        if (scheduler.schedule(observer, update)) {
            return true;
        }
        Wormholes.v("[ProjectionManager] dropped projected " + kind
            + " observer=" + (observer == null ? null : observer.getUniqueId())
            + " entity=" + entityId + " total=" + DROPPED_ENTITY_UPDATES.incrementAndGet());
        WormholesTelemetry.countFailure(ENTITY_UPDATE_DROPPED);
        return false;
    }

    private int countSpoofedEntities() {
        return interestSet.countSpoofedEntities() + closeQueue.countSpoofedEntities();
    }

    private List<ILocalPortal> collectActiveProjectors() {
        List<ILocalPortal> active = new ArrayList<ILocalPortal>();
        RtpProjectionProvider provider = rtpProjectionProvider;

        for (ILocalPortal portal : Wormholes.portalManager.getLocalPortals()) {
            if (provider != null && provider.supports(portal)) {
                active.add(portal);
                continue;
            }
            if (!portal.supportsProjections() || !portal.isProjecting() || portal.hasSurfaceSkin()) {
                continue;
            }
            if (!portal.isOpen()) {
                continue;
            }
            if (!portal.isMirrorMode() && !portal.hasTunnel()) {
                continue;
            }
            active.add(portal);
        }

        return active;
    }

    private List<ILocalPortal> collectSkinnedPortals() {
        List<ILocalPortal> skinned = new ArrayList<ILocalPortal>();
        for (ILocalPortal portal : Wormholes.portalManager.getLocalPortals()) {
            if (portal.hasSurfaceSkin() && !portal.isDestroyed() && portal.getWorld() != null) {
                skinned.add(portal);
            }
        }
        return skinned;
    }

    static int observerDiscoveryStart(int observerCount, int maxNewScans, long frameTick) {
        if (observerCount <= 0 || maxNewScans <= 0) {
            return 0;
        }
        long batch = Math.max(0L, frameTick - 1L);
        return (int) Math.floorMod(batch * Math.min(observerCount, maxNewScans), observerCount);
    }

    static int[] fairBudgetAllocations(int observerCount, int totalBudget, int perObserverBudget, long frameTick) {
        if (observerCount <= 0 || totalBudget <= 0 || perObserverBudget <= 0) {
            return new int[Math.max(0, observerCount)];
        }
        int[] allocations = new int[observerCount];
        long maximum = (long) observerCount * perObserverBudget;
        int remaining = (int) Math.min(totalBudget, Math.min(Integer.MAX_VALUE, maximum));
        long stride = Math.min(observerCount, totalBudget);
        long normalizedTick = Math.floorMod(frameTick, observerCount);
        int start = (int) ((normalizedTick * stride) % observerCount);
        while (remaining > 0) {
            boolean allocated = false;
            for (int offset = 0; offset < observerCount && remaining > 0; offset++) {
                int index = (start + offset) % observerCount;
                if (allocations[index] >= perObserverBudget) {
                    continue;
                }
                allocations[index]++;
                remaining--;
                allocated = true;
            }
            if (!allocated) {
                break;
            }
        }
        return allocations;
    }

    static <T> List<T> selectRoundRobin(List<T> values, int limit, int cursor) {
        if (values == null || values.isEmpty() || limit <= 0) {
            return List.of();
        }
        int selected = Math.min(limit, values.size());
        int start = Math.floorMod(cursor, values.size());
        List<T> result = new ArrayList<T>(selected);
        for (int offset = 0; offset < selected; offset++) {
            result.add(values.get((start + offset) % values.size()));
        }
        return result;
    }

    static ProjectionResolution resolveProjection(RtpProjectionProvider provider, ILocalPortal portal,
                                                  Player observer, RtpRimRenderer rimRenderer) {
        Objects.requireNonNull(portal, "portal");
        Objects.requireNonNull(observer, "observer");
        boolean rtp = provider != null && provider.supports(portal);
        RtpProjectionResult result = null;
        if (rtp) {
            RtpRimRenderer requiredRimRenderer = Objects.requireNonNull(rimRenderer, "rimRenderer");
            result = Objects.requireNonNull(provider.touch(portal, observer), "RTP projection result");
            RtpRimRenderer.Input input = new RtpRimRenderer.Input(
                    observer.getUniqueId(),
                    result.view(),
                    result.rimEnabled(),
                    result.attended(),
                    result.rotationMode(),
                    result.phase(),
                    result.elapsedMillis(),
                    result.durationMillis());
            Optional<RtpRimRenderer.Sample> sample = requiredRimRenderer.calculate(input);
            if (sample.isPresent()) {
                provider.dispatchRim(portal, observer, sample.get());
            }
        }
        if (!portal.supportsProjections() || !portal.isProjecting() || !portal.isOpen() || portal.hasSurfaceSkin()) {
            return ProjectionResolution.suppressed(rtp);
        }
        if (!rtp) {
            if (!portal.isMirrorMode() && !portal.hasTunnel()) {
                return ProjectionResolution.suppressed(false);
            }
            return ProjectionResolution.standard();
        }
        if (!result.projectionEnabled()) {
            return ProjectionResolution.suppressed(true);
        }
        Optional<RtpProjectionView.ReadyData> readyData = result.view().readyFor(observer.getUniqueId());
        if (readyData.isEmpty()) {
            return ProjectionResolution.suppressed(true);
        }
        RtpProjectionView.ReadyData ready = readyData.get();
        World targetWorld = provider.resolveTargetWorld(ready.target().worldKey());
        if (targetWorld == null) {
            return ProjectionResolution.suppressed(true);
        }
        return ProjectionResolution.rtp(PortalProjector.RtpProjectionTarget.from(ready, targetWorld));
    }

    static String formatLoc(Location loc) {
        if (loc == null) {
            return "null";
        }
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    static boolean isLookingTowardPortal(Location eye, Location center, double minimumDot) {
        if (eye == null || center == null) {
            return false;
        }
        if (eye.getWorld() != null && center.getWorld() != null && !eye.getWorld().equals(center.getWorld())) {
            return false;
        }
        double dx = center.getX() - eye.getX();
        double dy = center.getY() - eye.getY();
        double dz = center.getZ() - eye.getZ();
        double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
        if (distanceSquared <= 1.0E-6D) {
            return true;
        }
        double inverseDistance = 1.0D / Math.sqrt(distanceSquared);
        Vector direction = eye.getDirection();
        double dot = ((direction.getX() * dx) + (direction.getY() * dy) + (direction.getZ() * dz)) * inverseDistance;
        return dot >= minimumDot;
    }

    static boolean isObserverProjectionInterested(Location eye, Location center, ILocalPortal portal, boolean foveatedUnrendering) {
        if (!foveatedUnrendering) {
            return true;
        }
        return hasStablePortalSide(eye, portal, Settings.PROJECTION_SIDE_GRACE_DOT)
                && isLookingTowardPortal(eye, center, Settings.PROJECTION_OBSERVER_INTEREST_DOT);
    }

    static boolean isObserverProjectionInterested(Location eye, Location center, ILocalPortal portal) {
        return isObserverProjectionInterested(eye, center, portal, Settings.PROJECTION_FOVEATED_UNRENDERING);
    }

    static boolean hasStablePortalSide(Location eye, ILocalPortal portal, double minimumAbsoluteDot) {
        if (eye == null || portal == null || portal.getOrigin() == null || portal.getFrame() == null) {
            return false;
        }
        if (minimumAbsoluteDot <= 0.0D) {
            return true;
        }
        Direction normal = portal.getFrame().getNormal();
        return hasStablePortalSide(eye.getX(), eye.getY(), eye.getZ(),
                portal.getOrigin().getX(), portal.getOrigin().getY(), portal.getOrigin().getZ(),
                normal.x(), normal.y(), normal.z(), minimumAbsoluteDot);
    }

    static boolean hasStablePortalSide(double eyeX,
                                       double eyeY,
                                       double eyeZ,
                                       double originX,
                                       double originY,
                                       double originZ,
                                       double normalX,
                                       double normalY,
                                       double normalZ,
                                       double minimumAbsoluteDot) {
        if (minimumAbsoluteDot <= 0.0D) {
            return true;
        }
        double dx = eyeX - originX;
        double dy = eyeY - originY;
        double dz = eyeZ - originZ;
        double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
        if (distanceSquared <= 1.0E-6D) {
            return true;
        }
        double inverseDistance = 1.0D / Math.sqrt(distanceSquared);
        double dot = ((dx * normalX) + (dy * normalY) + (dz * normalZ)) * inverseDistance;
        return Math.abs(dot) >= minimumAbsoluteDot;
    }

    public void removeProjector(ILocalPortal portal) {
        interestSet.retirePortal(portal.getId());
    }

    public void removeProjector(Player player) {
        UUID id = player.getUniqueId();
        interestSet.closeObserver(id);
        interestSet.forgetObserver(id);
    }

    private void discardObserverProjectors(Player player) {
        UUID id = player.getUniqueId();
        interestSet.discardObserver(id);
        claimArbiter.discardObserver(id);
        clientChunkTracker.forget(id);
        interestSet.forgetObserver(id);
    }

    public void reprimeArrival(Player player) {
        if (player == null) {
            return;
        }
        FoliaScheduler.runEntity(Wormholes.instance, player, () -> removeProjector(player), 20L);
    }

    static boolean projectionsFrozen(long frozenUntilMillis, long nowMillis) {
        return frozenUntilMillis > nowMillis;
    }

    public long freezeProjections(long durationMillis) {
        if (durationMillis <= 0L) {
            projectionFrozenUntilMillis = 0L;
            return 0L;
        }
        long deadline = System.currentTimeMillis() + durationMillis;
        projectionFrozenUntilMillis = deadline;
        return deadline;
    }

    public int flushProjections() {
        Set<UUID> observersWithProjectors = interestSet.observerIds();
        int flushed = 0;
        for (Player player : new ArrayList<Player>(Wormholes.instance.getServer().getOnlinePlayers())) {
            UUID observerId = player.getUniqueId();
            boolean hadProjectors = observersWithProjectors.contains(observerId);
            removeProjector(player);
            clientChunkTracker.forget(observerId);
            if (hadProjectors) {
                flushed++;
            }
        }
        return flushed;
    }

    public void removeProjector(ILocalPortal portal, Player player) {
        interestSet.retire(portal.getId(), player.getUniqueId());
    }

    public void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }
        closed = true;
        skinRenderer.shutdown();
        if (taskId >= 0) {
            J.csr(taskId);
            taskId = -1;
        }
        Set<PortalProjector> closingSet = new HashSet<PortalProjector>(interestSet.snapshot());
        closingSet.addAll(closeQueue.pending());
        List<PortalProjector> closing = new ArrayList<PortalProjector>(closingSet);
        AtomicInteger pending = new AtomicInteger(closing.size());
        CountDownLatch completion = new CountDownLatch(closing.size());
        if (closing.isEmpty()) {
            finalizeShutdown();
        } else {
            for (PortalProjector projector : closing) {
                closeQueue.close(projector, () -> {
                    completion.countDown();
                    if (pending.decrementAndGet() == 0) {
                        finalizeShutdown();
                    }
                });
            }
        }
        interestSet.clear();
        observerTasksInFlight.clear();
        try {
            completion.await(2L, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            closeQueue.forceDiscardAll();
            finalizeShutdown();
        }
    }

    public void onSettingsReloaded() {
        interestSet.invalidateProjectionReuse();
        scheduleTick();
    }

    private boolean shouldUpdateBlocks() {
        int interval = Math.max(1, Settings.PROJECTION_REFRESH_INTERVAL_TICKS);
        return (tickCount - 1L) % interval == 0L;
    }

    private boolean shouldUpdateEntities() {
        int interval = Math.max(1, Settings.ENTITY_UPDATE_INTERVAL_TICKS);
        return (tickCount - 1L) % interval == 0L;
    }

    private void broadcastProjectedEntityAnimation(UUID entityId, EntityAnimationType type) {
        if (entityId == null || type == null) {
            return;
        }
        ViewServer viewServer = Wormholes.viewServer;
        if (viewServer != null) {
            viewServer.forwardAnimation(entityId, type);
        }
        dispatchProjectedEntityAnimation(entityId, type);
    }

    public void dispatchProjectedEntityAnimation(UUID entityId, EntityAnimationType type) {
        if (entityId == null || type == null) {
            return;
        }
        for (PortalProjector projector : interestSet.snapshot()) {
            Player observer = projector.getObserver();
            if (observer == null) {
                continue;
            }
            dispatchProjectedEntityUpdate(observer, entityId, "animation", () -> {
                if (observer.isOnline() && !projector.isClosed() && projector.hasProjectedEntity(entityId)) {
                    projector.sendProjectedEntityAnimation(entityId, type);
                }
            }, ENTITY_UPDATE_SCHEDULER);
        }
    }

    private void broadcastProjectedEntityHurt(UUID entityId, float yaw) {
        if (entityId == null) {
            return;
        }
        ViewServer viewServer = Wormholes.viewServer;
        if (viewServer != null) {
            viewServer.forwardHurt(entityId, yaw);
        }
        dispatchProjectedEntityHurt(entityId, yaw);
    }

    public void dispatchProjectedEntityHurt(UUID entityId, float yaw) {
        if (entityId == null) {
            return;
        }
        for (PortalProjector projector : interestSet.snapshot()) {
            Player observer = projector.getObserver();
            if (observer == null) {
                continue;
            }
            dispatchProjectedEntityUpdate(observer, entityId, "hurt", () -> {
                if (observer.isOnline() && !projector.isClosed() && projector.hasProjectedEntity(entityId)) {
                    projector.sendProjectedEntityHurt(entityId, yaw);
                }
            }, ENTITY_UPDATE_SCHEDULER);
        }
    }

    private Entity resolveAttackSource(Entity damager) {
        if (damager instanceof Projectile) {
            Projectile projectile = (Projectile) damager;
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Entity) {
                return (Entity) shooter;
            }
        }
        return damager;
    }

    private void scheduleTick() {
        if (closed || taskId >= 0) {
            return;
        }
        taskId = J.sr(() -> tick(), TICK_INTERVAL_TICKS);
        Wormholes.v("[ProjectionManager] tick scheduled (taskId=" + taskId + ", interval=" + TICK_INTERVAL_TICKS + "t, blockInterval=" + Settings.PROJECTION_REFRESH_INTERVAL_TICKS + "t, entityInterval=" + Settings.ENTITY_UPDATE_INTERVAL_TICKS + "t, range=" + Settings.PROJECTION_RANGE + ")");
    }

    private void finalizeShutdown() {
        if (!shutdownFinalized.compareAndSet(false, true)) {
            return;
        }
        claimArbiter.clear();
        viewProvider.close();
    }

    @FunctionalInterface
    interface ObserverFrameScheduler {
        boolean schedule(Player observer, Runnable frame, Runnable retired);
    }

    @FunctionalInterface
    interface EntityUpdateScheduler {
        boolean schedule(Player observer, Runnable update);
    }

    public interface RtpProjectionProvider {
        boolean supports(ILocalPortal portal);

        RtpProjectionResult touch(ILocalPortal portal, Player observer);

        World resolveTargetWorld(String worldKey);

        void dispatchRim(ILocalPortal portal, Player observer, RtpRimRenderer.Sample sample);
    }

    public record RtpProjectionResult(
            RtpProjectionView view,
            boolean projectionEnabled,
            boolean rimEnabled,
            boolean attended,
            RtpRotationMode rotationMode,
            RtpRimRenderer.Phase phase,
            long elapsedMillis,
            long durationMillis) {
        public RtpProjectionResult {
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(rotationMode, "rotationMode");
            Objects.requireNonNull(phase, "phase");
            if (elapsedMillis < 0L) {
                throw new IllegalArgumentException("elapsedMillis must be non-negative");
            }
            if (durationMillis < 0L) {
                throw new IllegalArgumentException("durationMillis must be non-negative");
            }
        }
    }

    record ProjectionResolution(boolean projectable, boolean rtp, PortalProjector.RtpProjectionTarget target) {
        private static ProjectionResolution standard() {
            return new ProjectionResolution(true, false, null);
        }

        private static ProjectionResolution rtp(PortalProjector.RtpProjectionTarget target) {
            return new ProjectionResolution(true, true, Objects.requireNonNull(target, "target"));
        }

        private static ProjectionResolution suppressed(boolean rtp) {
            return new ProjectionResolution(false, rtp, null);
        }
    }
}
