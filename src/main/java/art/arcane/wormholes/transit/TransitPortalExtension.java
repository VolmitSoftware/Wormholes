package art.arcane.wormholes.transit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.bukkit.entity.Entity;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.hook.PortalExtension;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;

/**
 * Per-portal transit state: momentum and orientation policy (null means "inherit the config default"),
 * membrane and bounce flags, and the transition profile. Momentum, orientation, membrane, and profile
 * replicate to linked portals; bounce stays local because a bouncing portal never sends anyone anywhere.
 */
public final class TransitPortalExtension implements PortalExtension {
    public static final String KEY = "transit";
    static final String MOMENTUM = KEY + ".momentum";
    static final String ORIENTATION = KEY + ".orientation";
    static final String MEMBRANE = KEY + ".membrane";
    static final String BOUNCE = KEY + ".bounce";
    static final String PROFILE = KEY + ".profile";

    /** A rig closure lives for the capture tick that built it; every member id maps to the same entry. */
    private static final long CONVOY_CACHE_MILLIS = 50L;

    private record CachedConvoy(ConvoyGraph graph, long stampMillis, boolean committed) {
        boolean fresh(long nowMillis) {
            return Math.abs(nowMillis - stampMillis) <= CONVOY_CACHE_MILLIS;
        }
    }

    private volatile MomentumPolicy momentum;
    private volatile OrientationPolicy orientation;
    private volatile boolean membrane;
    private volatile boolean bounce;
    private volatile TransitionProfile profile = TransitionProfile.NONE;
    private final ConcurrentHashMap<UUID, CachedConvoy> convoys = new ConcurrentHashMap<UUID, CachedConvoy>();

    /** The extension of a loaded local portal, or null when the portal is unknown or the manager is down. */
    public static TransitPortalExtension of(UUID portalId) {
        PortalManager manager = Wormholes.portalManager;
        if (portalId == null || manager == null) {
            return null;
        }
        ILocalPortal portal = manager.getLocalPortal(portalId);
        return portal instanceof LocalPortal local ? local.extension(TransitPortalExtension.class) : null;
    }

    @Override
    public String key() {
        return KEY;
    }

    public MomentumPolicy momentum() {
        return momentum;
    }

    public void setMomentum(MomentumPolicy policy) {
        momentum = policy;
    }

    public OrientationPolicy orientation() {
        return orientation;
    }

    public void setOrientation(OrientationPolicy policy) {
        orientation = policy;
    }

    public boolean isMembrane() {
        return membrane;
    }

    public void setMembrane(boolean enabled) {
        membrane = enabled;
    }

    public boolean isBounce() {
        return bounce;
    }

    public void setBounce(boolean enabled) {
        bounce = enabled;
    }

    public TransitionProfile profile() {
        return profile;
    }

    public void setProfile(TransitionProfile next) {
        profile = next == null ? TransitionProfile.NONE : next;
    }

    /** The momentum policy in force, falling back to the configured default. */
    public MomentumPolicy effectiveMomentum(TransitConfig config) {
        MomentumPolicy explicit = momentum;
        return explicit != null ? explicit : defaultMomentum(config);
    }

    /** The orientation policy in force, falling back to the configured default. */
    public OrientationPolicy effectiveOrientation(TransitConfig config) {
        OrientationPolicy explicit = orientation;
        return explicit != null ? explicit : defaultOrientation(config);
    }

    /** The rig around {@code traveler} for this capture tick, built once and shared by every member's gate call. */
    ConvoyGraph convoyFor(Entity traveler, long nowMillis, Supplier<ConvoyGraph> builder) {
        CachedConvoy cached = convoys.get(traveler.getUniqueId());
        if (cached != null && cached.fresh(nowMillis)) {
            return cached.graph();
        }
        convoys.values().removeIf(entry -> !entry.fresh(nowMillis));
        ConvoyGraph graph = builder.get();
        remember(graph, new CachedConvoy(graph, nowMillis, false));
        return graph;
    }

    /** Marks the rig rooted at {@code rootId} as admitted so the traversal routes it as one convoy. */
    void commitConvoy(UUID rootId, long nowMillis) {
        CachedConvoy cached = convoys.get(rootId);
        if (cached == null) {
            return;
        }
        remember(cached.graph(), new CachedConvoy(cached.graph(), nowMillis, true));
    }

    /** Returns and forgets the admitted rig rooted at {@code rootId}, or null when none was admitted this tick. */
    public ConvoyGraph takeCommittedConvoy(UUID rootId, long nowMillis) {
        CachedConvoy cached = convoys.get(rootId);
        if (cached == null || !cached.committed() || !cached.fresh(nowMillis)
            || !cached.graph().root().getUniqueId().equals(rootId)) {
            return null;
        }
        for (ConvoyGraph.Member member : cached.graph().members()) {
            convoys.remove(member.entity().getUniqueId(), cached);
        }
        return cached.graph();
    }

    private void remember(ConvoyGraph graph, CachedConvoy entry) {
        for (ConvoyGraph.Member member : graph.members()) {
            convoys.put(member.entity().getUniqueId(), entry);
        }
    }

    public static MomentumPolicy defaultMomentum(TransitConfig config) {
        return MomentumPolicy.of(MomentumPolicy.Mode.parse(config.momentumDefault, MomentumPolicy.Mode.PRESERVE));
    }

    public static OrientationPolicy defaultOrientation(TransitConfig config) {
        return OrientationPolicy.parse(config.orientationDefault, OrientationPolicy.FRAME);
    }

    @Override
    public void save(JSONObject portalJson) {
        MomentumPolicy savedMomentum = momentum;
        if (savedMomentum != null) {
            portalJson.put(MOMENTUM, savedMomentum.encode());
        }
        OrientationPolicy savedOrientation = orientation;
        if (savedOrientation != null) {
            portalJson.put(ORIENTATION, savedOrientation.name());
        }
        if (membrane) {
            portalJson.put(MEMBRANE, true);
        }
        if (bounce) {
            portalJson.put(BOUNCE, true);
        }
        TransitionProfile savedProfile = profile;
        if (!savedProfile.isNone()) {
            portalJson.put(PROFILE, savedProfile.encode());
        }
    }

    @Override
    public void load(JSONObject portalJson) {
        momentum = MomentumPolicy.decode(portalJson.optString(MOMENTUM, ""));
        orientation = OrientationPolicy.parse(portalJson.optString(ORIENTATION, ""), null);
        membrane = portalJson.optBoolean(MEMBRANE, false);
        bounce = portalJson.optBoolean(BOUNCE, false);
        profile = TransitionProfile.decode(portalJson.optString(PROFILE, ""));
    }

    @Override
    public void collectSync(Map<String, String> settings) {
        MomentumPolicy syncedMomentum = momentum;
        OrientationPolicy syncedOrientation = orientation;
        TransitionProfile syncedProfile = profile;
        if (syncedMomentum == null && syncedOrientation == null && !membrane && syncedProfile.isNone()) {
            return;
        }
        settings.put(MOMENTUM, syncedMomentum == null ? "" : syncedMomentum.encode());
        settings.put(ORIENTATION, syncedOrientation == null ? "" : syncedOrientation.name());
        settings.put(MEMBRANE, Boolean.toString(membrane));
        settings.put(PROFILE, syncedProfile.encode());
    }

    @Override
    public void applySync(Map<String, String> settings) {
        if (settings.containsKey(MOMENTUM)) {
            momentum = MomentumPolicy.decode(settings.get(MOMENTUM));
        }
        if (settings.containsKey(ORIENTATION)) {
            orientation = OrientationPolicy.parse(settings.get(ORIENTATION), null);
        }
        if (settings.containsKey(MEMBRANE)) {
            membrane = Boolean.parseBoolean(settings.get(MEMBRANE));
        }
        if (settings.containsKey(PROFILE)) {
            profile = TransitionProfile.decode(settings.get(PROFILE));
        }
    }
}
