package art.arcane.wormholes.transit;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalGate;
import art.arcane.wormholes.hook.TraversalPhase;
import art.arcane.wormholes.hook.TraversalVerdict;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.TransitBridge;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.service.WormholesHud;
import art.arcane.wormholes.util.AxisAlignedBB;

/**
 * Departure checks owned by the transit lane. A bouncing portal refuses everyone and a membrane refuses
 * anyone entering from its back side (both denials carry a bounce that {@link TransitObserver} turns into
 * a reflected velocity). A traveler attached to a rig is admitted only as a whole rig: the rig is closed
 * over vehicles, passengers, and leashes, capped, fitted to the aperture, held (Defer) until every member
 * is inside the capture zone, and every member is judged by the same gates as the root before the rig is
 * committed for the traversal to move in one piece.
 */
public final class TransitGate implements TraversalGate {
    private static final long WAITING_NOTICE_MILLIS = 2_000L;

    private final Function<LocalPortal, Collection<Entity>> candidates;
    private final Map<UUID, Long> waitingNotices = new ConcurrentHashMap<UUID, Long>();

    public TransitGate() {
        this(TransitGate::captureZoneEntities);
    }

    TransitGate(Function<LocalPortal, Collection<Entity>> candidates) {
        this.candidates = candidates;
    }

    @Override
    public int order() {
        return ORDER_TRANSIT;
    }

    @Override
    public TraversalVerdict evaluate(TraversalAttempt attempt) {
        if (attempt.phase() != TraversalPhase.DEPART) {
            return TraversalVerdict.ALLOW;
        }
        LocalPortal portal = attempt.portal();
        TransitPortalExtension transit = portal.extension(TransitPortalExtension.class);
        if (transit == null) {
            return TraversalVerdict.ALLOW;
        }
        if (transit.isBounce()) {
            return new TraversalVerdict.Deny(TransitMessages.BOUNCED, portalArgs(portal), true);
        }
        Traversive traversive = attempt.traversive();
        if (transit.isMembrane() && traversive != null && !traversive.isFrontSide()) {
            return new TraversalVerdict.Deny(TransitMessages.DENIED_MEMBRANE, portalArgs(portal), true);
        }
        return evaluateConvoy(attempt, portal, transit, traversive);
    }

    private TraversalVerdict evaluateConvoy(TraversalAttempt attempt, LocalPortal portal, TransitPortalExtension transit, Traversive traversive) {
        TransitConfig config = TransitSubsystem.config();
        ITunnel tunnel = attempt.tunnel();
        if (!config.convoyEnabled || tunnel == null || traversive == null) {
            return TraversalVerdict.ALLOW;
        }
        Entity traveler = attempt.traveler();
        long now = attempt.nowMillis();
        ConvoyGraph graph = transit.convoyFor(traveler, now,
            () -> ConvoyGraph.closure(traveler, candidates.apply(portal), config.convoyMaxEntities));
        if (!graph.isRig()) {
            return TraversalVerdict.ALLOW;
        }
        if (graph.overflow()) {
            return new TraversalVerdict.Deny(TransitMessages.DENIED_CONVOY_SIZE,
                WormholesLocalization.args(
                    MessageArgument.untrusted("portal", portalName(portal)),
                    MessageArgument.untrusted("count", Integer.toString(graph.size())),
                    MessageArgument.untrusted("value", Integer.toString(config.convoyMaxEntities))),
                true);
        }
        if (!traveler.getUniqueId().equals(graph.root().getUniqueId())) {
            return new TraversalVerdict.Defer(TransitMessages.CONVOY_WAITING);
        }
        PortalStructure structure = portal.getStructure();
        if (!graph.fits(structure, portal.getFrame())) {
            return new TraversalVerdict.Deny(TransitMessages.DENIED_CONVOY_FIT, portalArgs(portal), true);
        }
        AxisAlignedBB zone = structure == null ? null : structure.getCaptureZone();
        if (!graph.allInsidePlane(structure == null ? null : structure.getWorld(), zone)) {
            noticeWaiting(traveler, portal, now);
            return new TraversalVerdict.Defer(TransitMessages.CONVOY_WAITING);
        }
        if (tunnel instanceof UniversalTunnel
            && (!config.convoyCrossServerEnabled || graph.playerCount() != 1 || !(graph.root() instanceof Player))) {
            return new TraversalVerdict.Deny(TransitMessages.DENIED_CONVOY_MEMBER, portalArgs(portal), true);
        }
        for (ConvoyGraph.Member member : graph.members()) {
            if (member.entity().getUniqueId().equals(traveler.getUniqueId())) {
                continue;
            }
            if (!TransitBridge.memberMayTravel(portal, member.entity(), tunnel, now)) {
                return new TraversalVerdict.Deny(TransitMessages.DENIED_CONVOY_MEMBER, portalArgs(portal), true);
            }
        }
        transit.commitConvoy(traveler.getUniqueId(), now);
        return TraversalVerdict.ALLOW;
    }

    private void noticeWaiting(Entity traveler, LocalPortal portal, long now) {
        if (!(traveler instanceof Player player)) {
            return;
        }
        Long last = waitingNotices.get(player.getUniqueId());
        if (last != null && now - last.longValue() < WAITING_NOTICE_MILLIS) {
            return;
        }
        waitingNotices.put(player.getUniqueId(), Long.valueOf(now));
        if (Wormholes.instance != null) {
            WormholesHud.notice(player, Wormholes.text().component(player, TransitMessages.CONVOY_WAITING, portalArgs(portal)));
        }
    }

    static Collection<Entity> captureZoneEntities(LocalPortal portal) {
        PortalStructure structure = portal.getStructure();
        if (structure == null || structure.getWorld() == null || structure.getCaptureZone() == null) {
            return List.of();
        }
        Collection<Entity> entities = structure.getCaptureZone().getEntities(structure.getWorld());
        return entities == null ? List.of() : entities;
    }

    static MessageArgs portalArgs(LocalPortal portal) {
        return WormholesLocalization.args(MessageArgument.untrusted("portal", portalName(portal)));
    }

    private static String portalName(LocalPortal portal) {
        String name = portal.getName();
        return name == null ? "" : name;
    }
}
