package art.arcane.wormholes.network;

import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.RemotePortal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class PortalSyncService<P extends IPortal> {
    private static final ThreadLocal<Boolean> APPLYING_REMOTE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final AtomicBoolean closed = new AtomicBoolean();
    private final NetworkManager network;
    private final Supplier<List<P>> portalSource;
    private final Consumer<Runnable> globalDispatcher;
    private final PortalSettingsApplyQueue<P> localSettingsQueue;
    private final PortalSyncAccess<P> access;
    private final Supplier<RemotePortalRegistry> remoteRegistry;

    public PortalSyncService(NetworkManager network, Options<P> options) {
        this.network = network;
        this.portalSource = options.portalSource();
        this.globalDispatcher = options.globalDispatcher();
        this.localSettingsQueue = options.localSettingsQueue();
        this.access = options.access();
        this.remoteRegistry = options.remoteRegistry();
    }

    public void onPeerStateChanged(String peerName, boolean ready) {
        if (closed.get() || !ready) {
            return;
        }
        globalDispatcher.accept(() -> sendDirectory(peerName));
    }

    public void shutdown() {
        if (closed.compareAndSet(false, true)) {
            localSettingsQueue.shutdown();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void sendDirectory(String peerName) {
        if (closed.get()) {
            return;
        }
        List<PortalInfo> shared = new ArrayList<>();
        List<P> settingsSources = new ArrayList<>();
        for (P portal : portalSource.get()) {
            if (access.shareable(portal)) {
                shared.add(access.describe(portal));
                if (access.supportsSettings(portal)) {
                    settingsSources.add(portal);
                }
            }
        }
        network.send(peerName, new WireMessage.PortalDirectory(shared));
        for (P local : settingsSources) {
            network.send(peerName, settingsUpdate(local, true));
        }
    }

    public void broadcastPortal(P portal) {
        if (closed.get() || !network.isRunning()) {
            return;
        }
        if (access.shareable(portal)) {
            List<String> peers = peerNames();
            network.sendToPeers(peers, new WireMessage.PortalUpsert(access.describe(portal)));
            if (access.supportsSettings(portal)) {
                network.sendToPeers(peers, settingsUpdate(portal, true));
            }
        } else {
            broadcastRemove(portal.getId());
        }
    }

    public void broadcastRemove(UUID portalId) {
        if (closed.get() || !network.isRunning()) {
            return;
        }
        network.sendToPeers(peerNames(), new WireMessage.PortalRemove(portalId));
    }

    public static void applyRemote(Runnable task) {
        boolean previous = APPLYING_REMOTE.get();
        APPLYING_REMOTE.set(Boolean.TRUE);
        try {
            task.run();
        } finally {
            APPLYING_REMOTE.set(previous);
        }
    }

    public static boolean isApplyingRemote() {
        return APPLYING_REMOTE.get().booleanValue();
    }

    public void broadcastSettings(P portal) {
        if (closed.get() || portal == null || APPLYING_REMOTE.get().booleanValue()) {
            return;
        }
        if (!access.supportsSettings(portal) || !access.settingsSyncEnabled(portal)) {
            return;
        }
        applyToLinkedLocals(portal);
        if (network == null || !network.isRunning()) {
            return;
        }
        sendSettings(portal);
    }

    public void syncLinkedLocals(P portal) {
        if (closed.get() || portal == null || APPLYING_REMOTE.get().booleanValue()) {
            return;
        }
        if (!access.supportsSettings(portal) || !access.settingsSyncEnabled(portal)) {
            return;
        }
        applyToLinkedLocals(portal);
    }

    private void applyToLinkedLocals(P source) {
        if (access.receiverOnly(source)) {
            return;
        }
        List<P> counterparts = linkedLocalCounterparts(source);
        for (P counterpart : counterparts) {
            Map<String, String> payload = localPairSettings(source);
            applyRemote(() -> access.applySettings(counterpart, payload));
            access.refreshMenus(counterpart);
        }
    }

    private List<P> linkedLocalCounterparts(P source) {
        List<P> counterparts = new ArrayList<>(2);
        if (source == null || portalSource == null) {
            return counterparts;
        }
        UUID sourceId = source.getId();
        UUID forwardId = access.forwardLinkId(source);
        UUID counterpartId = access.counterpartId(source);
        if (forwardId == null && counterpartId == null) {
            return counterparts;
        }
        for (P candidate : portalSource.get()) {
            if (!access.supportsSettings(candidate)) {
                continue;
            }
            P local = candidate;
            UUID candidateId = local.getId();
            if (candidateId == null || candidateId.equals(sourceId)) {
                continue;
            }
            if (!candidateId.equals(forwardId) && !candidateId.equals(counterpartId)) {
                continue;
            }
            if (access.rtp(local) || !access.settingsSyncEnabled(local)) {
                continue;
            }
            if (!containsPortal(counterparts, candidateId)) {
                counterparts.add(local);
            }
        }
        return counterparts;
    }

    private boolean containsPortal(List<P> portals, UUID portalId) {
        for (P portal : portals) {
            if (portalId.equals(portal.getId())) {
                return true;
            }
        }
        return false;
    }

    public void broadcastSettingsToggle(P portal) {
        if (closed.get() || portal == null || !network.isRunning() || APPLYING_REMOTE.get().booleanValue()) {
            return;
        }
        if (!access.supportsSettings(portal)) {
            return;
        }
        sendSettings(portal);
    }

    public void broadcastRemoteCache(P portal) {
        if (closed.get() || portal == null || network == null || !network.isRunning() || APPLYING_REMOTE.get().booleanValue()) {
            return;
        }
        if (!access.supportsSettings(portal) || !access.gateway(portal)) {
            return;
        }
        network.sendToPeers(peerNames(), settingsUpdate(portal, true));
    }

    private void sendSettings(P local) {
        String linkedPeer = access.linkedPeer(local);
        WireMessage.PortalSettingsUpdate update = settingsUpdate(local, false);
        if (linkedPeer != null) {
            network.send(linkedPeer, update);
            return;
        }
        network.sendToPeers(peerNames(), update);
    }

    private List<String> peerNames() {
        List<NetworkManager.PeerStatus> statuses = network.status();
        List<String> names = new ArrayList<>(statuses.size());
        for (NetworkManager.PeerStatus peer : statuses) {
            names.add(peer.name());
        }
        return names;
    }

    public void applySettingsUpdate(String peerName, WireMessage.PortalSettingsUpdate update) {
        if (closed.get() || update == null) {
            return;
        }
        UUID portalId = update.portalId();
        Map<String, String> settings = update.settings();
        RemotePortalRegistry registry = remoteRegistry.get();
        if (registry != null) {
            RemotePortal remote = registry.get(peerName, portalId);
            if (remote != null) {
                PortalSettingsCodec.applyToRemote(remote, settings);
            }
        }
        if (Boolean.parseBoolean(settings.get(PortalSettingsCodec.KEY_REMOTE_CACHE_ONLY))) {
            return;
        }
        P target = findLinkedLocal(peerName, portalId);
        if (target != null) {
            localSettingsQueue.enqueue(target, settings);
        }
    }

    private WireMessage.PortalSettingsUpdate settingsUpdate(P local, boolean remoteCacheOnly) {
        Map<String, String> settings = access.collectSettings(local);
        if (remoteCacheOnly) {
            settings.put(PortalSettingsCodec.KEY_REMOTE_CACHE_ONLY, Boolean.TRUE.toString());
        }
        return new WireMessage.PortalSettingsUpdate(local.getId(), settings);
    }

    private P findLinkedLocal(String peerName, UUID senderPortalId) {
        if (peerName == null || senderPortalId == null || portalSource == null) {
            return null;
        }
        for (P portal : portalSource.get()) {
            if (!access.supportsSettings(portal)) {
                continue;
            }
            if (peerName.equals(access.linkedPeer(portal)) && senderPortalId.equals(access.remoteDestinationId(portal))) {
                return portal;
            }
        }
        return null;
    }

    private Map<String, String> localPairSettings(P portal) {
        Map<String, String> settings = access.collectSettings(portal);
        settings.remove(PortalSettingsCodec.KEY_OUTGOING_TRAVERSALS);
        settings.remove(PortalSettingsCodec.KEY_INCOMING_TRAVERSALS);
        settings.remove(PortalSettingsCodec.KEY_SETTINGS_SYNC);
        return settings;
    }

    public record Options<P extends IPortal>(Supplier<List<P>> portalSource, Consumer<Runnable> globalDispatcher,
                                             Supplier<RemotePortalRegistry> remoteRegistry, PortalSyncAccess<P> access,
                                             PortalSettingsApplyQueue<P> localSettingsQueue) {
    }
}
