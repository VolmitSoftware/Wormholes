package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireCapability;
import art.arcane.wormholes.network.WireMessage;

import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends one {@link LoadBeacon} per {@code beacon-interval-sec} to every peer whose negotiated
 * capabilities include LOAD_BEACON, plus one immediately when drain flips. Peers without a ready link
 * or sideband have no negotiated capabilities and are skipped.
 */
public final class LoadBeaconService {
    private final NetworkManager network;
    private final Logger logger;
    private final DrainMode drain;
    private final LongSupplier clock;
    private volatile ServerLoadSource source = ServerLoadSource.EMPTY;
    private volatile long lastSentMillis;

    public LoadBeaconService(NetworkManager network, Logger logger, DrainMode drain, LongSupplier clock) {
        this.network = network;
        this.logger = logger;
        this.drain = drain;
        this.clock = clock;
        drain.setListener(this::sendNow);
    }

    public void setSource(ServerLoadSource source) {
        this.source = source == null ? ServerLoadSource.EMPTY : source;
    }

    public ServerLoadSource source() {
        return source;
    }

    public boolean tick() {
        NetworkConfig config = network.activeConfig();
        long now = clock.getAsLong();
        if (now - lastSentMillis < config.policy.beaconIntervalSec * 1_000L) {
            return false;
        }
        sendNow();
        return true;
    }

    public void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "net: load beacon failed", e);
        }
    }

    public LoadBeacon current() {
        ServerLoadSource active = source;
        return new LoadBeacon(active.online(), active.max(), active.reserved(), active.tps(), active.msptP95(), drain.isDraining(),
            network.localCapabilities(), clock.getAsLong());
    }

    public void sendNow() {
        lastSentMillis = clock.getAsLong();
        WireMessage.LoadBeaconMessage message = new WireMessage.LoadBeaconMessage(current());
        for (NetworkConfig.PeerEntry peer : network.peers()) {
            if (network.peerSupports(peer.name, WireCapability.LOAD_BEACON)) {
                network.send(peer.name, message);
            }
        }
    }
}
