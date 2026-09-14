package art.arcane.wormholes.access;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.api.traversal.TraversalDecision;
import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.RemotePortal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.UUID;

public final class PortalAccessDiagnostics {
    private static final RejectionThrottle THROTTLE = new RejectionThrottle();

    private PortalAccessDiagnostics() {
    }

    public static void frameDenied(String phase, IPortal portal, Entity entity) {
        if (Settings.DEBUG && entity instanceof Player player) {
            report(phase, frameReason(phase, portal, player), portal, player);
        }
    }

    public static void destinationMissing(LocalPortal portal, Entity entity) {
        if (Settings.DEBUG && entity instanceof Player player) {
            report("DEPART", "destination_missing", portal, player);
        }
    }

    public static void gateDenied(TraversalAttempt attempt, String reason) {
        if (Settings.DEBUG && !attempt.screening() && attempt.traveler() instanceof Player player) {
            report(attempt.phase().name(), reason, attempt.portal(), player);
        }
    }

    public static void accessDenied(TraversalAttempt attempt) {
        if (Settings.DEBUG && !attempt.screening() && attempt.traveler() instanceof Player player) {
            AccessPortalExtension access = attempt.portal().extension(AccessPortalExtension.class);
            String reason = access != null && access.role(player.getUniqueId()) == PortalRole.DENIED
                ? "denied_role" : access != null && access.whitelistOnly() ? "role_whitelist" : "permission_node";
            report(attempt.phase().name(), reason, attempt.portal(), player);
        }
    }

    public static void decisionDenied(TraversalAttempt attempt, TraversalDecision decision) {
        if (Settings.DEBUG && !attempt.screening() && attempt.traveler() instanceof Player player) {
            String reason = decision.outcome() + ":" + decision.providerId() + ":" + decision.reason();
            RejectionKey key = new RejectionKey(attempt.portal().getId(), player.getUniqueId(), attempt.phase().name(), reason);
            if (THROTTLE.acquire(key, System.currentTimeMillis())) {
                Wormholes.v("[access] phase=" + attempt.phase() + " reason=traversal_cost outcome=" + decision.outcome()
                    + " provider=" + quoted(decision.providerId()) + " decisionReason=" + quoted(decision.reason())
                    + " " + describe(attempt.portal(), player));
            }
        }
    }

    private static void report(String phase, String reason, IPortal portal, Player player) {
        RejectionKey key = new RejectionKey(portal.getId(), player.getUniqueId(), phase, reason);
        if (THROTTLE.acquire(key, System.currentTimeMillis())) {
            Wormholes.v("[access] phase=" + phase + " reason=" + reason + " " + describe(portal, player));
        }
    }

    static String frameReason(String phase, IPortal portal, Player player) {
        boolean departure = "DEPART".equals(phase);
        if (portal instanceof ILocalPortal local) {
            if (("ARRIVE".equals(phase) || "ARRIVE_AFTER_TELEPORT".equals(phase)) && !local.isOpen()) {
                return "portal_closed";
            }
            if (local.isMirrorMode()) {
                return "mirror_mode";
            }
            if (!PortalAdmission.bypassesAccess(player) && !(departure ? local.isOutgoingTraversalsEnabled() : local.isIncomingTraversalsEnabled())) {
                return departure ? "outgoing_disabled" : "incoming_disabled";
            }
        } else if (portal instanceof RemotePortal remote) {
            if (!remote.isOpen()) {
                return "portal_closed";
            }
            if (remote.isMirroredMirrorMode()) {
                return "mirror_mode";
            }
            if (!PortalAdmission.bypassesAccess(player) && !remote.isMirroredIncomingTraversalsEnabled()) {
                return "incoming_disabled";
            }
        }
        return "permission_node";
    }

    static String describe(IPortal portal, Player player) {
        String nameNode = PermissionKeys.node(PermissionKeys.sanitize(portal.getName()));
        String identity = "player=" + quoted(player.getName()) + " playerId=" + player.getUniqueId()
            + " op=" + player.isOp() + " accessBypass=" + PortalAdmission.bypassesAccess(player) + " portal=" + portal.getId() + " name=" + quoted(portal.getName());
        if (portal instanceof ILocalPortal local) {
            AccessPortalExtension access = local instanceof LocalPortal concrete
                ? concrete.extension(AccessPortalExtension.class) : null;
            String keyNode = access == null ? nameNode : access.permissionNode();
            boolean alias = PortalAdmission.legacyNameNodeAlias();
            return identity + " remote=false open=" + local.isOpen() + " mirror=" + local.isMirrorMode()
                + " outgoing=" + local.isOutgoingTraversalsEnabled() + " incoming=" + local.isIncomingTraversalsEnabled()
                + " mode=" + local.getPermissionMode() + " keyNode=" + keyNode
                + " keyGranted=" + player.hasPermission(keyNode) + " nameNode=" + nameNode
                + " nameGranted=" + player.hasPermission(nameNode) + " nameAlias=" + alias
                + (access == null ? "" : " role=" + access.role(player.getUniqueId())
                    + " roleWhitelist=" + access.whitelistOnly() + " groupGranted=" + PortalAdmission.grantedByGroup(player, access)
                    + " owner=" + player.getUniqueId().equals(((LocalPortal) local).getOwner()));
        }
        if (portal instanceof RemotePortal remote) {
            return identity + " remote=true peer=" + quoted(remote.getServer().getName())
                + " open=" + remote.isOpen() + " mirror=" + remote.isMirroredMirrorMode()
                + " outgoing=" + remote.isMirroredOutgoingTraversalsEnabled()
                + " incoming=" + remote.isMirroredIncomingTraversalsEnabled()
                + " mode=" + remote.getMirroredPermissionMode() + " nameNode=" + nameNode
                + " nameGranted=" + player.hasPermission(nameNode);
        }
        return identity;
    }

    private static String quoted(String value) {
        return "\"" + value.replace('\\', '/').replace('"', '\'').replace('\n', ' ').replace('\r', ' ')
            .replace('\t', ' ').replace('\u0085', ' ').replace('\u2028', ' ').replace('\u2029', ' ') + "\"";
    }

    record RejectionKey(UUID portalId, UUID playerId, String phase, String reason) {
    }

    static final class RejectionThrottle {
        static final long INTERVAL_MILLIS = 5_000L;
        private static final int MAX_KEYS = 1_024;
        private final LinkedHashMap<RejectionKey, Long> reports = new LinkedHashMap<>();

        synchronized boolean acquire(RejectionKey key, long nowMillis) {
            Long previous = reports.get(key);
            if (previous != null && nowMillis - previous.longValue() < INTERVAL_MILLIS) {
                return false;
            }
            if (reports.size() >= MAX_KEYS) {
                reports.values().removeIf(issued -> nowMillis - issued.longValue() >= INTERVAL_MILLIS);
                if (reports.size() >= MAX_KEYS) {
                    reports.pollFirstEntry();
                }
            }
            reports.put(key, nowMillis);
            return true;
        }
    }
}
