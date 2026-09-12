package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.util.VIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Floods this server's signed announce on the announce interval, when its advertised endpoints
 * change, and directly to every peer that just linked. The epoch is a persisted counter under
 * identity/epoch that bumps on every network start and on every endpoint change, so receivers can
 * order announces and tombstones from the same name without trusting clocks.
 */
public final class PeerAnnouncer {
    static final long INITIAL_DELAY_MILLIS = 5_000L;
    private static final String EPOCH_FILE = "epoch";

    private final NetworkManager network;
    private final Logger logger;
    private final Path epochFile;
    private final LongSupplier clock;
    private volatile long epoch;
    private volatile long startedAtMillis;
    private volatile long lastAnnounceMillis;
    private volatile String lastEndpoints;

    public PeerAnnouncer(NetworkManager network, Logger logger, Path dataDirectory, LongSupplier clock) {
        this.network = network;
        this.logger = logger;
        this.epochFile = dataDirectory.resolve("identity").resolve(EPOCH_FILE);
        this.clock = clock;
        this.epoch = readEpoch();
    }

    public long epoch() {
        return epoch;
    }

    /** Bumps the epoch for a new network generation; the first flood waits for the initial delay. */
    public void start() {
        epoch = bumpEpoch();
        startedAtMillis = clock.getAsLong();
        lastAnnounceMillis = 0L;
        lastEndpoints = null;
    }

    /** One-second cadence. Returns true when an announce was flooded. */
    public boolean tick() {
        NetworkConfig config = network.activeConfig();
        if (!config.mesh.enabled) {
            return false;
        }
        long now = clock.getAsLong();
        if (now - startedAtMillis < INITIAL_DELAY_MILLIS) {
            return false;
        }
        PeerAnnounce announce = network.buildAnnounce(epoch, now);
        String endpoints = endpointKey(announce);
        boolean changed = lastEndpoints != null && !lastEndpoints.equals(endpoints);
        if (changed) {
            epoch = bumpEpoch();
            announce = network.buildAnnounce(epoch, now);
            endpoints = endpointKey(announce);
            logger.info("net: advertised endpoints changed, announcing epoch " + epoch);
        }
        long intervalMillis = config.mesh.announceIntervalSec * 1_000L;
        if (!changed && lastAnnounceMillis != 0L && now - lastAnnounceMillis < intervalMillis) {
            return false;
        }
        flood(announce, now);
        lastEndpoints = endpoints;
        return true;
    }

    public void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "net: peer announce failed", e);
        }
    }

    public PeerAnnounce announceNow() {
        long now = clock.getAsLong();
        PeerAnnounce announce = network.buildAnnounce(epoch, now);
        flood(announce, now);
        lastEndpoints = endpointKey(announce);
        return announce;
    }

    /** A raw link just came up: hand the peer our current announce directly so it does not wait for the interval. */
    public void onPeerReady(String peerName) {
        if (!network.activeConfig().mesh.enabled) {
            return;
        }
        network.send(peerName, new WireMessage.PeerAnnounceMessage(network.buildAnnounce(epoch, clock.getAsLong())));
    }

    private void flood(PeerAnnounce announce, long now) {
        lastAnnounceMillis = now;
        List<String> names = new ArrayList<>();
        for (NetworkConfig.PeerEntry peer : network.peers()) {
            names.add(peer.name);
        }
        if (!names.isEmpty()) {
            network.sendToPeers(names, new WireMessage.PeerAnnounceMessage(announce));
        }
    }

    private static String endpointKey(PeerAnnounce announce) {
        return announce.advertiseHost() + '|' + announce.wormholePort() + '|' + announce.gameEndpoint() + '|' + announce.privateGameEndpoint();
    }

    private long readEpoch() {
        try {
            if (Files.isRegularFile(epochFile)) {
                return Long.parseLong(Files.readString(epochFile, StandardCharsets.UTF_8).trim());
            }
        } catch (IOException | NumberFormatException e) {
            logger.warning("net: could not read " + epochFile + ", restarting the epoch counter: " + e.getMessage());
        }
        return 0L;
    }

    private synchronized long bumpEpoch() {
        long next = Math.max(epoch, readEpoch()) + 1L;
        try {
            Files.createDirectories(epochFile.getParent());
            VIO.writeAllBytes(epochFile.toFile(), Long.toString(next).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warning("net: could not persist " + epochFile + ": " + e.getMessage());
        }
        return next;
    }
}
