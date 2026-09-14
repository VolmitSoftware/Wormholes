package art.arcane.wormholes.access;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.AccessConfig;
import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalGate;
import art.arcane.wormholes.hook.TraversalPhase;
import art.arcane.wormholes.hook.TraversalVerdict;
import art.arcane.wormholes.localization.AccessMessages;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admission on the traversal hot path: DENIED refuses outright, one trusted role turns the portal
 * into a whitelist, an allowed permission group grants, and the stable
 * {@code wormholes.portal.<key>} node is read in the portal's own permission mode.
 */
public final class AccessGate implements TraversalGate {
    private static final long USE_CACHE_MILLIS = 5_000L;
    private static final int USE_CACHE_PRUNE_AT = 1024;

    private final ClaimAdapters claims;
    private final Set<UUID> reportedLegacyNodes = ConcurrentHashMap.newKeySet();
    private final Map<String, CachedUse> useCache = new ConcurrentHashMap<>();
    private volatile AccessConfig config = new AccessConfig();

    public AccessGate(ClaimAdapters claims) {
        this.claims = Objects.requireNonNull(claims, "claims");
    }

    public void applySettings(AccessConfig settings) {
        config = settings == null ? new AccessConfig() : settings;
        useCache.clear();
    }

    public void clear() {
        reportedLegacyNodes.clear();
        useCache.clear();
    }

    @Override
    public int order() {
        return ORDER_ACCESS;
    }

    @Override
    public TraversalVerdict evaluate(TraversalAttempt attempt) {
        if (!(attempt.traveler() instanceof Player player) || PortalAdmission.bypassesAccess(player)) {
            return TraversalVerdict.ALLOW;
        }
        LocalPortal portal = attempt.portal();
        AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
        if (access == null) {
            return TraversalVerdict.ALLOW;
        }
        reportLegacyNodeOnce(portal, access);
        if (!PortalAdmission.allows(portal, player)) {
            PortalAccessDiagnostics.accessDenied(attempt);
            return refuse(portal);
        }
        if (!claimAllowsUse(portal, player, attempt)) {
            PortalAccessDiagnostics.gateDenied(attempt, "land_claim");
            return new TraversalVerdict.Deny(AccessMessages.DENIED_CLAIM, portalArgument(portal), true);
        }
        return TraversalVerdict.ALLOW;
    }

    /**
     * Land-claim check at traversal time. Off by default, one representative cell at the aperture
     * centre, and the answer is cached per portal, player, and phase for five seconds. Building and
     * linking fail closed when a claim plugin cannot be asked; travel through a portal that already
     * stands does not, unless the operator turns grandfathering off.
     */
    private boolean claimAllowsUse(LocalPortal portal, Player player, TraversalAttempt attempt) {
        if (!config.claimCheckOnUse) {
            return true;
        }
        PlacementKind kind = attempt.phase() == TraversalPhase.ARRIVE ? PlacementKind.ARRIVE : PlacementKind.USE;
        String key = portal.getId() + "/" + player.getUniqueId() + "/" + kind;
        long now = attempt.nowMillis();
        CachedUse cached = useCache.get(key);
        if (cached != null && cached.expiresAtMillis > now) {
            return cached.allowed;
        }
        Location centre = portal.getStructure().getCenter();
        List<int[]> cells = List.of(new int[] {centre.getBlockX(), centre.getBlockY(), centre.getBlockZ()});
        PlacementDecision decision = claims.evaluate(
            new PlacementRequest(player.getUniqueId(), portal.getStructure().getWorld(), cells, kind));
        boolean allowed = decision.allowed()
            || (config.grandfatherExistingPortals && decision.status() == PlacementDecision.Status.FAILURE);
        if (useCache.size() >= USE_CACHE_PRUNE_AT) {
            useCache.values().removeIf(entry -> entry.expiresAtMillis <= now);
        }
        useCache.put(key, new CachedUse(allowed, now + USE_CACHE_MILLIS));
        return allowed;
    }

    private record CachedUse(boolean allowed, long expiresAtMillis) {
    }

    static TraversalVerdict refuse(LocalPortal portal) {
        return new TraversalVerdict.Deny(AccessMessages.DENIED_ROLE, portalArgument(portal), true);
    }

    static MessageArgs portalArgument(LocalPortal portal) {
        return WormholesLocalization.args(MessageArgument.untrusted("portal", portal.getName()));
    }

    /**
     * With the alias off the name-derived node is no longer read anywhere; when a portal's key has
     * drifted from its name, say so once so the old node can be renamed to the key.
     */
    private void reportLegacyNodeOnce(LocalPortal portal, AccessPortalExtension access) {
        if (config.legacyNameNodeEnabled || access.matchesNameDerivedNode()) {
            return;
        }
        if (reportedLegacyNodes.add(portal.getId())) {
            Wormholes.i("access: portal " + portal.getId() + " permission key " + access.permissionKey()
                + " (legacy node " + PermissionKeys.sanitize(portal.getName()) + " no longer read)");
        }
    }
}
