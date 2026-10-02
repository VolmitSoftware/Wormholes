package art.arcane.wormholes.portal.vanilla;

import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.player.PlayerRespawnAccess;
import art.arcane.volmlib.nativelib.player.RespawnPolicy;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class BukkitEndReturnPreview implements AutoCloseable {
    private static final long REFRESH_TICKS = 40L;
    private static final PortalFrame FRAME = PortalFrame.canonical(Direction.U);

    private final Host host;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Map<UUID, Long> retries = new ConcurrentHashMap<>();
    private final AtomicLong revisions = new AtomicLong();
    private volatile boolean closed;

    public BukkitEndReturnPreview(Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    public static BukkitEndReturnPreview create(Plugin plugin) {
        return new BukkitEndReturnPreview(new BukkitHost(Objects.requireNonNull(plugin, "plugin")));
    }

    public PortalProjector.RtpProjectionTarget target(Player observer, long tick) {
        if (closed) {
            return null;
        }
        UUID observerId = observer.getUniqueId();
        Long retryAt = retries.get(observerId);
        if (retryAt != null && tick < retryAt) {
            return null;
        }
        RespawnPolicy snapshot;
        try {
            snapshot = host.snapshot(observer);
        } catch (RuntimeException failure) {
            retries.put(observerId, tick + REFRESH_TICKS);
            host.failed(observerId, failure);
            return null;
        }
        if (snapshot == null) {
            return null;
        }
        retries.remove(observerId);
        Entry entry = entries.get(observerId);
        if (entry == null || !snapshot.equals(entry.snapshot)) {
            entry = new Entry(snapshot);
            entries.put(observerId, entry);
        }
        if (!entry.pending && tick >= entry.refreshAt) {
            entry.pending = true;
            entry.refreshAt = tick + REFRESH_TICKS;
            Entry requested = entry;
            try {
                host.resolve(snapshot, entry.fallback).whenComplete((spawn, failure) -> publish(observerId, requested, spawn, failure));
            } catch (RuntimeException failure) {
                publish(observerId, requested, null, failure);
            }
        }
        return entry.target;
    }

    public void forget(UUID observerId) {
        entries.remove(observerId);
        retries.remove(observerId);
    }

    @Override
    public void close() {
        closed = true;
        entries.clear();
        retries.clear();
    }

    private void publish(UUID observerId, Entry entry, ResolvedSpawn spawn, Throwable failure) {
        if (closed || entries.get(observerId) != entry) {
            return;
        }
        if (failure != null) {
            entry.target = null;
            entry.pending = false;
            host.failed(observerId, failure);
            return;
        }
        Location location = spawn.location();
        if (spawn.fallback()) {
            entry.fallback = location;
        }
        PortalProjector.RtpProjectionTarget previous = entry.target;
        if (previous == null || previous.world() != location.getWorld() || previous.originX() != location.getX()
            || previous.originY() != location.getY() || previous.originZ() != location.getZ()) {
            entry.target = new PortalProjector.RtpProjectionTarget(location.getWorld(), location.getX(), location.getY(),
                location.getZ(), FRAME, revisions.incrementAndGet());
        }
        entry.pending = false;
    }

    public interface Host {
        RespawnPolicy snapshot(Player observer);

        CompletableFuture<ResolvedSpawn> resolve(RespawnPolicy snapshot, Location fallback);

        void failed(UUID observerId, Throwable failure);
    }

    public record ResolvedSpawn(Location location, boolean fallback) {
        public ResolvedSpawn {
            location = Objects.requireNonNull(location, "location").clone();
            Objects.requireNonNull(location.getWorld(), "world");
        }
    }

    private static final class Entry {
        private final RespawnPolicy snapshot;
        private volatile PortalProjector.RtpProjectionTarget target;
        private Location fallback;
        private volatile boolean pending;
        private long refreshAt;

        private Entry(RespawnPolicy snapshot) {
            this.snapshot = snapshot;
        }
    }

    private static final class BukkitHost implements Host {
        private final Plugin plugin;
        private final PlayerRespawnAccess respawns;

        private BukkitHost(Plugin plugin) {
            this.plugin = plugin;
            this.respawns = NativeAdapters.find(PlayerRespawnAccess.class).orElse(null);
        }

        @Override
        public RespawnPolicy snapshot(Player observer) {
            return respawns == null ? null : respawns.snapshot(observer);
        }

        @Override
        public CompletableFuture<ResolvedSpawn> resolve(RespawnPolicy snapshot, Location cachedFallback) {
            CompletableFuture<ResolvedSpawn> result = new CompletableFuture<>();
            if (snapshot.personal() == null) {
                fallback(snapshot, cachedFallback, result);
            } else {
                load(snapshot.personal().location()).whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                        return;
                    }
                    schedule(snapshot.personal().location(), () -> validate(snapshot, cachedFallback, result), result);
                });
            }
            return result;
        }

        @Override
        public void failed(UUID observerId, Throwable failure) {
            plugin.getLogger().log(Level.WARNING, "Could not resolve End return preview for " + observerId, failure);
        }

        private void validate(RespawnPolicy snapshot, Location cachedFallback, CompletableFuture<ResolvedSpawn> result) {
            try {
                Location validated = respawns.validate(snapshot).orElse(null);
                if (validated == null) {
                    fallback(snapshot, cachedFallback, result);
                } else {
                    result.complete(new ResolvedSpawn(validated, false));
                }
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        }

        private void fallback(RespawnPolicy snapshot, Location cachedFallback, CompletableFuture<ResolvedSpawn> result) {
            if (cachedFallback != null) {
                result.complete(new ResolvedSpawn(cachedFallback, true));
                return;
            }
            Location shared = snapshot.shared().location();
            schedule(shared, () -> {
                try {
                    respawns.findSharedSpawn(snapshot.shared()).whenComplete((location, failure) -> {
                        if (failure != null) {
                            result.completeExceptionally(failure);
                        } else {
                            result.complete(new ResolvedSpawn(location, true));
                        }
                    });
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            }, result);
        }

        private CompletableFuture<Void> load(Location location) {
            List<CompletableFuture<Chunk>> loads = new ArrayList<>(9);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    loads.add(WormholesPlatform.loadChunk(plugin, location.getWorld(), (location.getBlockX() >> 4) + x,
                        (location.getBlockZ() >> 4) + z, true));
                }
            }
            return CompletableFuture.allOf(loads.toArray(CompletableFuture<?>[]::new));
        }

        private void schedule(Location location, Runnable task, CompletableFuture<ResolvedSpawn> result) {
            if (!FoliaScheduler.runRegion(plugin, location, task)) {
                result.completeExceptionally(new IllegalStateException("End return preview region task was rejected"));
            }
        }
    }

}
