package art.arcane.wormholes.render.clientview;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.protocol.player.User;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.rtp.RtpRimRenderer;
import art.arcane.wormholes.render.ClientViewPortalSource;
import art.arcane.wormholes.render.PortalProjector;

public final class ClientViewObserver {
    static final long EFFECT_TOUCH_TTL_NANOS = 3_000_000_000L;

    private final UUID id;
    private final ArrayList<UUID> interest;
    private final ArrayList<ILocalPortal> candidates;
    private final HashMap<UUID, ILocalPortal> portals;
    private final HashMap<UUID, PortalProjector.RtpProjectionTarget> targets;
    private final HashMap<UUID, ClientViewPortalSource> sources;
    private final HashMap<UUID, HashMap<UUID, ClientViewPortalSource>> nestedSources;
    private final HashMap<UUID, Location> reflectedEyes;
    private final HashSet<UUID> ownedScratch;
    private final ConcurrentHashMap<UUID, RtpRimRenderer.Sample> rims;
    private final ConcurrentHashMap<UUID, Long> effectTouches;
    private volatile User user;
    private volatile Player player;
    private volatile String brand;
    private volatile boolean offered;
    private volatile Set<UUID> ownedPortals;
    private Location eye;
    private long frameTick;

    public ClientViewObserver(UUID id, User user) {
        this.id = Objects.requireNonNull(id, "id");
        this.user = user;
        this.interest = new ArrayList<UUID>(8);
        this.candidates = new ArrayList<ILocalPortal>(8);
        this.portals = new HashMap<UUID, ILocalPortal>(8);
        this.targets = new HashMap<UUID, PortalProjector.RtpProjectionTarget>(4);
        this.sources = new HashMap<UUID, ClientViewPortalSource>(8);
        this.nestedSources = new HashMap<UUID, HashMap<UUID, ClientViewPortalSource>>(2);
        this.reflectedEyes = new HashMap<UUID, Location>(2);
        this.ownedScratch = new HashSet<UUID>(8);
        this.rims = new ConcurrentHashMap<UUID, RtpRimRenderer.Sample>(4);
        this.effectTouches = new ConcurrentHashMap<UUID, Long>(8);
        this.ownedPortals = Set.of();
    }

    public UUID id() {
        return id;
    }

    public User user() {
        return user;
    }

    public void user(User value) {
        if (value != null) {
            user = value;
        }
    }

    public Player player() {
        return player;
    }

    public void player(Player value) {
        player = value;
    }

    public String brand() {
        return brand;
    }

    public void brand(String value) {
        brand = value;
    }

    public boolean offered() {
        return offered;
    }

    public void markOffered() {
        offered = true;
    }

    public Set<UUID> ownedPortals() {
        return ownedPortals;
    }

    public boolean attending() {
        return !ownedPortals.isEmpty() || !effectTouches.isEmpty();
    }

    void touchEffects(UUID portalId, long nanos) {
        effectTouches.put(portalId, nanos);
    }

    void effects(List<UUID> out, long nanos) {
        Iterator<Map.Entry<UUID, Long>> iterator = effectTouches.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (nanos - entry.getValue() > EFFECT_TOUCH_TTL_NANOS) {
                iterator.remove();
            } else {
                out.add(entry.getKey());
            }
        }
    }

    HashSet<UUID> ownedScratch() {
        ownedScratch.clear();
        return ownedScratch;
    }

    void publishOwned() {
        if (!ownedScratch.equals(ownedPortals)) {
            ownedPortals = Set.copyOf(ownedScratch);
        }
    }

    void beginFrame(Player observer, Location observerEye, List<ILocalPortal> interested, List<ILocalPortal> projectable,
                    Map<UUID, PortalProjector.RtpProjectionTarget> rtpTargets, long tick) {
        player = observer;
        eye = observerEye;
        frameTick = tick;
        interest.clear();
        candidates.clear();
        reflectedEyes.clear();
        for (int i = 0; i < projectable.size(); i++) {
            ILocalPortal portal = projectable.get(i);
            interest.add(portal.getId());
            candidates.add(portal);
            track(portal, rtpTargets);
        }
    }

    private void track(ILocalPortal portal, Map<UUID, PortalProjector.RtpProjectionTarget> rtpTargets) {
        UUID portalId = portal.getId();
        portals.put(portalId, portal);
        PortalProjector.RtpProjectionTarget target = rtpTargets.get(portalId);
        if (target == null) {
            targets.remove(portalId);
        } else {
            targets.put(portalId, target);
        }
    }

    void prune(long staleBefore) {
        Iterator<Map.Entry<UUID, ClientViewPortalSource>> sourceIterator = sources.entrySet().iterator();
        while (sourceIterator.hasNext()) {
            if (sourceIterator.next().getValue().touchedTick() < staleBefore) {
                sourceIterator.remove();
            }
        }
        Iterator<Map.Entry<UUID, HashMap<UUID, ClientViewPortalSource>>> nestedIterator = nestedSources.entrySet().iterator();
        while (nestedIterator.hasNext()) {
            HashMap<UUID, ClientViewPortalSource> children = nestedIterator.next().getValue();
            children.values().removeIf(source -> source.touchedTick() < staleBefore);
            if (children.isEmpty()) {
                nestedIterator.remove();
            }
        }
        Iterator<UUID> portalIterator = portals.keySet().iterator();
        while (portalIterator.hasNext()) {
            UUID portalId = portalIterator.next();
            if (!sources.containsKey(portalId) && !interest.contains(portalId) && !nestedChild(portalId) && !candidate(portalId)
                && !effectTouches.containsKey(portalId)) {
                portalIterator.remove();
                targets.remove(portalId);
                rims.remove(portalId);
            }
        }
    }

    void rim(UUID portalId, RtpRimRenderer.Sample sample) {
        if (sample == null) {
            rims.remove(portalId);
        } else {
            rims.put(portalId, sample);
        }
    }

    RtpRimRenderer.Sample rim(UUID portalId) {
        return rims.get(portalId);
    }

    void clearFrame() {
        rims.clear();
        effectTouches.clear();
        interest.clear();
        candidates.clear();
        targets.clear();
        sources.clear();
        nestedSources.clear();
        reflectedEyes.clear();
        portals.clear();
        ownedPortals = Set.of();
    }

    List<UUID> interest() {
        return interest;
    }

    ILocalPortal portal(UUID portalId) {
        return portals.get(portalId);
    }

    PortalProjector.RtpProjectionTarget target(UUID portalId) {
        return targets.get(portalId);
    }

    List<ILocalPortal> candidates() {
        return candidates;
    }

    ClientViewPortalSource nestedSource(UUID parentId, UUID childId) {
        HashMap<UUID, ClientViewPortalSource> children = nestedSources.get(parentId);
        return children == null ? null : children.get(childId);
    }

    void nestedSource(UUID parentId, UUID childId, ClientViewPortalSource source) {
        nestedSources.computeIfAbsent(parentId, id -> new HashMap<UUID, ClientViewPortalSource>(4)).put(childId, source);
    }

    Location reflectedEye(UUID parentId) {
        return reflectedEyes.get(parentId);
    }

    void reflectedEye(UUID parentId, Location reflected) {
        reflectedEyes.put(parentId, reflected);
    }

    private boolean nestedChild(UUID portalId) {
        for (HashMap<UUID, ClientViewPortalSource> children : nestedSources.values()) {
            if (children.containsKey(portalId)) {
                return true;
            }
        }
        return false;
    }

    private boolean candidate(UUID portalId) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).getId().equals(portalId)) {
                return true;
            }
        }
        return false;
    }

    Set<UUID> sourceIds() {
        return sources.keySet();
    }

    ClientViewPortalSource source(UUID portalId) {
        return sources.get(portalId);
    }

    void source(UUID portalId, ClientViewPortalSource source) {
        sources.put(portalId, source);
    }

    Location eye() {
        return eye;
    }

    long frameTick() {
        return frameTick;
    }
}
