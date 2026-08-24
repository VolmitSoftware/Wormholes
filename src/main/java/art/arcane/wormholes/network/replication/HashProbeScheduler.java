package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class HashProbeScheduler {
    private final NetworkManager network;
    private final ChunkReplicationManager replication;
    private final Map<String, Integer> rotationCursors = new ConcurrentHashMap<>();
    private boolean running;
    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> probeTask;
    private volatile long intervalSec = 30L;
    private volatile long scheduledIntervalSec;
    private volatile int chunksPerProbe = 16;

    public HashProbeScheduler(NetworkManager network, ChunkReplicationManager replication) {
        this.network = network;
        this.replication = replication;
    }

    public synchronized void configure(long intervalSec, int chunksPerProbe) {
        long normalizedIntervalSec = Math.max(1L, intervalSec);
        int normalizedChunksPerProbe = Math.max(1, Math.min(ChunkHashProbe.MAX_ENTRIES, chunksPerProbe));
        boolean intervalChanged = this.intervalSec != normalizedIntervalSec;
        this.intervalSec = normalizedIntervalSec;
        this.chunksPerProbe = normalizedChunksPerProbe;
        if (running && intervalChanged) {
            ScheduledExecutorService active = scheduler;
            if (active != null) {
                schedule(active, normalizedIntervalSec);
            }
        }
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Wormholes-HashProbe");
            thread.setDaemon(true);
            return thread;
        });
        scheduler = executor;
        schedule(executor, intervalSec);
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        ScheduledFuture<?> activeTask = probeTask;
        probeTask = null;
        scheduledIntervalSec = 0L;
        if (activeTask != null) {
            activeTask.cancel(false);
        }
        ScheduledExecutorService active = scheduler;
        scheduler = null;
        if (active != null) {
            active.shutdownNow();
        }
        rotationCursors.clear();
    }

    long scheduledIntervalSec() {
        return scheduledIntervalSec;
    }

    int configuredChunksPerProbe() {
        return chunksPerProbe;
    }

    public void probeOnce() {
        for (String peerName : currentPeers()) {
            List<ReplicationStreamKey> streams = replication.streamsFor(peerName);
            if (streams.isEmpty()) {
                continue;
            }
            int cursor = rotationCursors.getOrDefault(peerName, 0);
            if (cursor >= streams.size()) {
                cursor = 0;
            }
            List<ChunkHashProbe.ChunkHashEntry> entries = new ArrayList<>(Math.min(chunksPerProbe, streams.size()));
            int taken = 0;
            int index = cursor;
            while (taken < chunksPerProbe && taken < streams.size()) {
                ReplicationStreamKey stream = streams.get(index % streams.size());
                long sequence = replication.lastBroadcastSeq(peerName, stream);
                long hash = replication.canonicalHash(peerName, stream);
                entries.add(new ChunkHashProbe.ChunkHashEntry(stream, sequence, hash));
                index++;
                taken++;
            }
            rotationCursors.put(peerName, index % streams.size());
            if (entries.isEmpty()) {
                continue;
            }
            ChunkHashProbe probe = new ChunkHashProbe(entries);
            network.send(peerName, new WireMessage.ChunkHashProbeMessage(probe));
        }
    }

    private List<String> currentPeers() {
        List<String> names = new ArrayList<>();
        for (NetworkManager.PeerSnapshot snapshot : network.peerSnapshots()) {
            if (snapshot.handshakeComplete() && !snapshot.disconnected()) {
                names.add(snapshot.name());
            }
        }
        return names;
    }

    private void schedule(ScheduledExecutorService executor, long periodSec) {
        ScheduledFuture<?> previous = probeTask;
        if (previous != null) {
            previous.cancel(false);
        }
        probeTask = executor.scheduleWithFixedDelay(this::probeOnce, periodSec, periodSec, TimeUnit.SECONDS);
        scheduledIntervalSec = periodSec;
    }
}
