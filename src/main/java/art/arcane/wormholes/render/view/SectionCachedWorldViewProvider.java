package art.arcane.wormholes.render.view;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.render.BukkitProjectorBlocks;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

final class SectionCachedWorldViewProvider implements ProjectionWorldViewProvider, ProjectionWorldChangeTracker.ChangeListener {
    private static final long BYTES_PER_MEGABYTE = 1_048_576L;

    private final Plugin plugin;
    private final ProjectionWorldChangeTracker tracker;
    private final SectionCache<BlockData, Material> cache;
    private final Map<World, SectionCachedWorldView> views;
    private final Map<UUID, SectionCachedWorldView> viewsById;
    private final Queue<Runnable> deferredChanges;
    private final Thread owner;
    private int tick;

    SectionCachedWorldViewProvider(Plugin plugin, ProjectionWorldChangeTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.cache = new SectionCache<BlockData, Material>(BukkitProjectorBlocks.defaults(), limits());
        this.views = new ConcurrentHashMap<World, SectionCachedWorldView>();
        this.viewsById = new ConcurrentHashMap<UUID, SectionCachedWorldView>();
        this.deferredChanges = new ConcurrentLinkedQueue<Runnable>();
        this.owner = Thread.currentThread();
        this.tick = 0;
        if (tracker != null) {
            tracker.addListener(this);
        }
    }

    @Override
    public ProjectionWorldView view(World world) {
        if (world == null) {
            return null;
        }
        SectionCachedWorldView view = views.get(world);
        if (view != null) {
            return view;
        }
        if (Thread.currentThread() != owner) {
            return new LiveWorldView(world);
        }
        return views.computeIfAbsent(world, this::createView);
    }

    @Override
    public ProjectionWorldView authoritativeView(World world) {
        ProjectionWorldView view = view(world);
        return view instanceof SectionCachedWorldView cachedView ? cachedView.live() : view;
    }

    @Override
    public void tick() {
        tick++;
        Runnable change;
        while ((change = deferredChanges.poll()) != null) {
            change.run();
        }
        cache.tick(tick);
    }

    @Override
    public void reconfigure() {
        cache.configure(limits());
    }

    @Override
    public long cachedBytes() {
        return cache.bytes();
    }

    @Override
    public int cachedSections() {
        return cache.sectionCount();
    }

    @Override
    public void close() {
        if (tracker != null) {
            tracker.removeListener(this);
        }
        cache.clear();
        views.clear();
        viewsById.clear();
        deferredChanges.clear();
    }

    @Override
    public void blockChanged(UUID worldId, long blockKey) {
        if (Thread.currentThread() != owner) {
            deferredChanges.add(() -> blockChanged(worldId, blockKey));
            return;
        }
        SectionCachedWorldView view = viewsById.get(worldId);
        if (view != null) {
            view.sections().blockChanged(ProjectionCellKey.unpackX(blockKey), ProjectionCellKey.unpackY(blockKey),
                ProjectionCellKey.unpackZ(blockKey));
        }
    }

    @Override
    public void columnChanged(UUID worldId, int chunkX, int chunkZ) {
        if (Thread.currentThread() != owner) {
            deferredChanges.add(() -> columnChanged(worldId, chunkX, chunkZ));
            return;
        }
        SectionCachedWorldView view = viewsById.get(worldId);
        if (view != null) {
            view.sections().columnChanged(chunkX, chunkZ);
        }
    }

    @Override
    public void worldCleared(UUID worldId) {
        if (Thread.currentThread() != owner) {
            deferredChanges.add(() -> worldCleared(worldId));
            return;
        }
        SectionCachedWorldView view = viewsById.remove(worldId);
        if (view == null) {
            return;
        }
        views.remove(view.getWorld());
        cache.release(view.sections());
    }

    private SectionCachedWorldView createView(World world) {
        LiveWorldView live = new LiveWorldView(world);
        BukkitSectionSource source = new BukkitSectionSource(world, Material.AIR.createBlockData());
        SectionCache<BlockData, Material>.WorldSections sections = cache.world(source,
            world.getMinHeight() >> 4, (world.getMaxHeight() - 1) >> 4);
        Set<Long> inFlight = ConcurrentHashMap.newKeySet();
        SectionCachedWorldView view = new SectionCachedWorldView(world, live, sections,
            (requested, chunkX, chunkZ) -> requestChunk(requested, chunkX, chunkZ, inFlight), owner);
        viewsById.put(world.getUID(), view);
        return view;
    }

    private void requestChunk(World world, int chunkX, int chunkZ, Set<Long> inFlight) {
        Long key = Long.valueOf((((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL));
        if (!inFlight.add(key)) {
            return;
        }
        try {
            WormholesPlatform.loadChunk(plugin, world, chunkX, chunkZ).whenComplete((chunk, error) -> {
                inFlight.remove(key);
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING, "Projection chunk load failed at " + world.getName()
                        + " " + chunkX + "," + chunkZ, error);
                    return;
                }
                if (chunk != null && tracker != null) {
                    tracker.markChanged(world.getUID(), chunkX << 4, chunkZ << 4);
                }
            });
        } catch (RuntimeException failure) {
            inFlight.remove(key);
            plugin.getLogger().log(Level.WARNING, "Projection chunk load request failed at " + world.getName()
                + " " + chunkX + "," + chunkZ, failure);
        }
    }

    private static SectionCache.Limits limits() {
        return new SectionCache.Limits(Settings.PROJECTION_SECTION_CACHE,
            Settings.PROJECTION_SECTION_CACHE_MAX_MB * BYTES_PER_MEGABYTE,
            Settings.PROJECTION_SECTION_CACHE_CHUNKS_PER_TICK,
            Settings.PROJECTION_SECTION_CACHE_TTL_TICKS);
    }
}
