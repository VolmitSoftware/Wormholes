package art.arcane.wormholes.integration.fawe;

import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.wrappers.WorldWrapper;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.util.eventbus.EventBus;
import com.sk89q.worldedit.util.eventbus.EventHandler.Priority;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class FaweProjectionIntegration implements Listener, AutoCloseable {
    private final ConcurrentHashMap<String, WorldGeneration> worlds = new ConcurrentHashMap<>();
    private final FaweAppliedChunkInvalidator invalidator;
    private final Mode mode;
    private final FaweEditQuiescence quiescence = new FaweEditQuiescence();
    private final AtomicLong generations = new AtomicLong();
    private CompletableFuture<Void> preparing;
    private EventBus eventBus;
    private Logger logger;
    private String allowedExtent;
    private volatile boolean closed;

    FaweProjectionIntegration(Options options) {
        invalidator = new FaweAppliedChunkInvalidator(options.tracker(), options.scheduler());
        mode = Objects.requireNonNull(options.mode());
    }

    public static FaweProjectionIntegration enable(JavaPlugin plugin, WorldChangeTracker tracker) {
        Objects.requireNonNull(plugin);
        FaweProjectionIntegration integration = new FaweProjectionIntegration(
                new Options(tracker, task -> schedule(plugin, task), detectMode()));
        try {
            integration.start(plugin, WorldEdit.getInstance().getEventBus());
            plugin.getLogger().info(integration.mode == Mode.PATCHED
                    ? "FAWE projection tracking enabled with edit completion hooks"
                    : "FAWE queued projection tracking enabled; native edit tracking and safe hot reload require completion hooks");
            return integration;
        } catch (RuntimeException | LinkageError exception) {
            try {
                integration.close();
            } catch (RuntimeException | LinkageError cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    public boolean supportsReload() {
        return mode == Mode.PATCHED;
    }

    public synchronized CompletionStage<Void> prepareReload(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("FAWE reload timeout must be positive");
        }
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("FAWE integration is closed"));
        }
        if (!supportsReload()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "FAWE does not expose edit completion hooks; restart the server to unload Wormholes safely"));
        }
        if (preparing != null && !preparing.isCompletedExceptionally()) {
            return preparing;
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        preparing = completion;
        quiescence.prepare().thenCompose(ignored -> invalidator.flush())
                .orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS).whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        synchronized (FaweProjectionIntegration.this) {
                            if (preparing == completion && !closed) {
                                preparing = null;
                                quiescence.resume();
                            }
                        }
                        completion.completeExceptionally(failure);
                    } else {
                        completion.complete(null);
                    }
                });
        return completion;
    }

    public synchronized void cancelReload() {
        quiescence.resume();
        if (preparing != null && !preparing.isDone()) {
            preparing.cancel(false);
        }
        preparing = null;
    }

    @Override
    public synchronized void close() {
        closed = true;
        quiescence.prepare();
        invalidator.close();
        if (eventBus != null) {
            eventBus.unregister(this);
            eventBus = null;
        }
        HandlerList.unregisterAll(this);
        worlds.clear();
        removeExtentAllowance();
    }

    @Subscribe(priority = Priority.VERY_LATE)
    public void onEditSession(EditSessionEvent event) {
        if (closed || event.isCancelled() || event.getStage() != EditSession.Stage.BEFORE_CHANGE || event.getWorld() == null) {
            return;
        }
        WorldGeneration world = worlds.get(event.getWorld().getNameUnsafe());
        if (world == null) {
            return;
        }
        FaweEditQuiescence.Session session = mode == Mode.PATCHED ? quiescence.admit() : null;
        if (mode == Mode.PATCHED && session == null) {
            event.setCancelled(true);
            return;
        }
        try {
            if (session != null) {
                event.onSessionClose(session::close);
            }
            Extent extent = event.getExtent();
            FaweAppliedChunkProcessor.Binding binding = new FaweAppliedChunkProcessor.Binding(
                    world.worldId(), world.generation(), invalidator, quiescence, session,
                    mode == Mode.PATCHED && supportsNativeWorld(WorldWrapper.unwrap(event.getWorld())));
            Extent processed = extent.addPostProcessor(new FaweAppliedChunkProcessor(binding));
            if (processed != extent) {
                addExtentAllowance();
                event.setExtent(processed);
            }
        } catch (RuntimeException | LinkageError failure) {
            if (session != null) {
                session.close();
            }
            disableTracking(failure);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        if (!closed) {
            rememberWorld(event.getWorld());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        World world = event.getWorld();
        WorldGeneration removed = worlds.remove(world.getName());
        if (removed != null) {
            invalidator.retireWorld(removed.worldId());
        }
    }

    void start(Plugin plugin, EventBus bus) {
        logger = plugin.getLogger();
        for (World world : plugin.getServer().getWorlds()) {
            rememberWorld(world);
        }
        if (mode == Mode.PATCHED) {
            addExtentAllowance();
        }
        eventBus = bus;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        eventBus.register(this);
    }

    static Mode detectMode() {
        return detectMode(IBatchProcessor.class.getClassLoader());
    }

    static Mode detectMode(ClassLoader loader) {
        try {
            EditSessionEvent.class.getMethod("onSessionClose", Runnable.class);
            IBatchProcessor.class.getMethod("capturePostProcessor", IChunk.class);
            IBatchProcessor.class.getMethod("requiresBeforeState");
            IBatchProcessor.class.getMethod("applicationStarted");
            IBatchProcessor.class.getMethod("applicationCompleted");
            BukkitWorld.class.getMethod("appliedChangeSupportVersion");
            Class.forName("com.fastasyncworldedit.core.util.task.NativeChangeScope", false,
                    loader);
            return Mode.PATCHED;
        } catch (NoSuchMethodException | ClassNotFoundException exception) {
            return Mode.STANDARD;
        }
    }

    private static boolean supportsNativeWorld(Extent world) {
        return world instanceof BukkitWorld bukkitWorld && bukkitWorld.appliedChangeSupportVersion() == 1;
    }

    private static void schedule(Plugin plugin, Runnable task) {
        if (!FoliaScheduler.runGlobal(plugin, task, 1L)) {
            throw new IllegalStateException("Cannot schedule FAWE projection invalidation while Wormholes is unavailable");
        }
    }

    private synchronized void disableTracking(Throwable failure) {
        if (closed) {
            return;
        }
        try {
            close();
        } catch (RuntimeException | LinkageError cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        logger.log(Level.WARNING, "FAWE projection tracking stopped after an integration failure; FAWE edits remain available", failure);
    }

    private void rememberWorld(World world) {
        worlds.computeIfAbsent(world.getName(), ignored -> {
            WorldGeneration generation = new WorldGeneration(world.getUID(), generations.incrementAndGet());
            invalidator.rememberWorld(generation.worldId(), generation.generation());
            return generation;
        });
    }

    private synchronized void addExtentAllowance() {
        if (closed) {
            return;
        }
        Settings.EXTENT settings = Settings.settings().EXTENT;
        String className = FaweNativeChangeExtent.class.getName();
        List<String> current = settings.ALLOWED_PLUGINS;
        for (String allowed : current) {
            if (className.toLowerCase(Locale.ROOT).contains(allowed.toLowerCase(Locale.ROOT))) {
                return;
            }
        }
        if (allowedExtent == null) {
            allowedExtent = new String(className);
        }
        List<String> allowed = new ArrayList<>(current);
        allowed.add(allowedExtent);
        settings.ALLOWED_PLUGINS = allowed;
    }

    private synchronized void removeExtentAllowance() {
        if (allowedExtent == null) {
            return;
        }
        Settings.EXTENT settings = Settings.settings().EXTENT;
        List<String> allowed = new ArrayList<>(settings.ALLOWED_PLUGINS);
        allowed.removeIf(entry -> entry == allowedExtent);
        settings.ALLOWED_PLUGINS = allowed;
        allowedExtent = null;
    }

    record Options(WorldChangeTracker tracker, Consumer<Runnable> scheduler, Mode mode) {
    }

    enum Mode {
        STANDARD,
        PATCHED
    }

    private record WorldGeneration(UUID worldId, long generation) {
    }
}
