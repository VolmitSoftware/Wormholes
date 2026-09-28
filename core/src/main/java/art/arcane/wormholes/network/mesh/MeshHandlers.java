package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.Handshake;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.WireMessageHandler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Per-NetworkManager inbound handler for the federation frames. {@code peerName} is the trusted link
 * the frame was authenticated on: for a PEER_ANNOUNCE that equals the announced name when the peer
 * spoke for itself and names the introducer otherwise; for a PEER_TOMBSTONE it is the issuer.
 */
public final class MeshHandlers implements WireMessageHandler {
    static final long DEDUPE_WINDOW_MILLIS = 30_000L;

    private final NetworkManager network;
    private final Logger logger;
    private final PeerQuarantineStore quarantine;
    private final TombstoneService tombstones;
    private final MeshMemberTable members;
    private final PeerLoadTable loads;
    private final AnnounceDedupe dedupe;
    private final Set<String> pendingLogged = ConcurrentHashMap.newKeySet();
    private final Set<String> keyMismatchLogged = ConcurrentHashMap.newKeySet();
    private volatile Consumer<String> peerRemovedListener;
    private volatile PortalQueryService portalQueries;

    public MeshHandlers(NetworkManager network, Logger logger, PeerQuarantineStore quarantine, TombstoneService tombstones, MeshMemberTable members, PeerLoadTable loads) {
        this.network = network;
        this.logger = logger;
        this.quarantine = quarantine;
        this.tombstones = tombstones;
        this.members = members;
        this.loads = loads;
        this.dedupe = new AnnounceDedupe(DEDUPE_WINDOW_MILLIS, () -> network.activeConfig().mesh.announceRateLimitPerSourcePerMin);
    }

    public MeshMemberTable members() {
        return members;
    }

    /** Answers PORTAL_QUERY from the local portals and completes outbound queries; unset until the plugin runtime binds it. */
    public void setPortalQueryService(PortalQueryService service) {
        this.portalQueries = service;
    }

    /** Called after a peer was forgotten locally (tombstone or operator remove) so portal directories drop it too. */
    public void setPeerRemovedListener(Consumer<String> listener) {
        this.peerRemovedListener = listener;
    }

    @Override
    public boolean handle(String peerName, WireMessage message) {
        if (message instanceof WireMessage.PeerAnnounceMessage announce) {
            handleAnnounce(peerName, announce.announce());
            return true;
        }
        if (message instanceof WireMessage.PeerTombstoneMessage tombstone) {
            handleTombstone(peerName, tombstone.tombstone());
            return true;
        }
        if (message instanceof WireMessage.LoadBeaconMessage beacon) {
            loads.record(peerName, beacon.beacon(), System.currentTimeMillis());
            return true;
        }
        if (message instanceof WireMessage.PortalQuery || message instanceof WireMessage.PortalQueryResult) {
            PortalQueryService queries = portalQueries;
            return queries == null || queries.handle(peerName, message);
        }
        return false;
    }

    /** Trusts and routes a quarantined peer; false when nothing is waiting under that name. */
    public boolean acceptQuarantined(String name) {
        PeerQuarantineStore.Entry entry = quarantine.get(name);
        if (entry == null) {
            return false;
        }
        accept(entry.announce(), entry.introducer(), System.currentTimeMillis());
        return true;
    }

    public boolean rejectQuarantined(String name) {
        boolean removed = quarantine.remove(name);
        if (removed) {
            pendingLogged.remove(name);
            logger.info("net: rejected introduced peer " + name);
        }
        return removed;
    }

    /** Drops every mesh record for a name after the trust and route entries are gone. */
    public void forget(String name) {
        members.remove(name);
        quarantine.remove(name);
        pendingLogged.remove(name);
        keyMismatchLogged.remove(name);
    }

    public static long tombstoneTtlMillis(NetworkConfig.MeshConfig mesh) {
        return TimeUnit.DAYS.toMillis(Math.max(1, mesh == null ? 30 : mesh.tombstoneTtlDays));
    }

    private void handleAnnounce(String via, PeerAnnounce announce) {
        NetworkConfig.MeshConfig mesh = network.activeConfig().mesh;
        String name = announce.name();
        if (!mesh.enabled || name == null || name.isBlank() || name.equals(network.getLocalName())) {
            return;
        }
        if (!announce.verify()) {
            logger.warning("net: dropped peer announce for " + name + " via " + via + ": bad signature");
            return;
        }
        long now = System.currentTimeMillis();
        if (!dedupe.admit(name, announce.epoch(), via, now)) {
            return;
        }
        if (tombstones.ignoresAnnounce(name, announce.epoch(), now, tombstoneTtlMillis(mesh))) {
            return;
        }
        byte[] trustedKey = network.trustedKey(name);
        if (trustedKey != null) {
            if (!Handshake.sameKey(trustedKey, announce.publicKey())) {
                if (keyMismatchLogged.add(name)) {
                    logger.warning("net: ignoring peer announce for " + name + " via " + via + ": key " + announce.fingerprint()
                        + " differs from the trusted key; /wh server remove " + name + " then re-import to accept the new identity");
                }
                return;
            }
            if (announce.epoch() >= members.epochOf(name, 0L)) {
                network.savePeer(mergeRoute(announce));
                members.record(announce, via, now);
            }
            return;
        }
        switch (PeerIntroductionPolicy.decide(announce, via, mesh)) {
            case ACCEPT -> accept(announce, via, now);
            case QUARANTINE -> {
                if (quarantine.put(announce, via, now) && pendingLogged.add(name)) {
                    logger.info("net: peer " + name + " introduced by " + via + " is waiting for approval: /wh network accept " + name);
                }
            }
            case REJECT -> {
            }
        }
    }

    private void accept(PeerAnnounce announce, String introducer, long now) {
        String name = announce.name();
        network.trustKey(name, announce.publicKey());
        network.savePeer(mergeRoute(announce));
        members.record(announce, introducer, now);
        quarantine.remove(name);
        tombstones.clear(name);
        pendingLogged.remove(name);
        logger.info("net: accepted peer " + name + " introduced by " + introducer + " key " + announce.fingerprint());
    }

    private NetworkConfig.PeerEntry mergeRoute(PeerAnnounce announce) {
        NetworkConfig.PeerEntry route = announce.toRoute();
        NetworkConfig.PeerEntry existing = network.getPeer(announce.name());
        if (existing != null) {
            route.useProxy = existing.useProxy;
            route.fallbackHosts = existing.fallbackHosts;
        }
        return route;
    }

    /**
     * A tombstone from a trusted issuer. The bilateral half - the issuer telling us it dropped this
     * server - always applies; removing a third party network-wide is the mesh feature, so a server
     * with the mesh off keeps its operator-configured peers.
     */
    private void handleTombstone(String issuer, PeerTombstone tombstone) {
        byte[] issuerKey = network.trustedKey(issuer);
        if (issuerKey == null || !tombstone.verify(issuerKey)) {
            logger.warning("net: dropped peer tombstone for " + tombstone.name() + " from " + issuer + ": bad signature");
            return;
        }
        String name = tombstone.name();
        if (name.equals(network.getLocalName())) {
            logger.info("net: peer " + issuer + " removed this server from its network; forgetting " + issuer);
            network.removePeer(issuer);
            notifyRemoved(issuer);
            return;
        }
        if (!network.activeConfig().mesh.enabled) {
            return;
        }
        tombstones.record(tombstone);
        if (quarantine.matches(name, tombstone.publicKey())) {
            quarantine.remove(name);
            pendingLogged.remove(name);
        }
        byte[] trustedKey = network.trustedKey(name);
        if (trustedKey != null && Handshake.sameKey(trustedKey, tombstone.publicKey())) {
            logger.info("net: peer " + name + " removed network-wide by " + issuer);
            network.removePeer(name);
            notifyRemoved(name);
        }
    }

    private void notifyRemoved(String name) {
        Consumer<String> listener = peerRemovedListener;
        if (listener != null) {
            listener.accept(name);
        }
    }
}
