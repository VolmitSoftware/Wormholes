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

public final class FaweProjectionIntegration implements Listener, AutoCloseable {
    private final ConcurrentHashMap<String, WorldGeneration> worlds = new ConcurrentHashMap<>();
    private final FaweAppliedChunkInvalidator invalidator;
    private final FaweEditQuiescence quiescence = new FaweEditQuiescence();
    private final AtomicLong generations = new AtomicLong();
    private CompletableFuture<Void> preparing;
    private EventBus eventBus;
    private String allowedExtent;
    private volatile boolean closed;

    FaweProjectionIntegration(WorldChangeTracker tracker, Consumer<Runnable> scheduler) {
        invalidator = new FaweAppliedChunkInvalidator(tracker, scheduler);
    }

    public static FaweProjectionIntegration enable(JavaPlugin plugin, WorldChangeTracker tracker) {
        Objects.requireNonNull(plugin);
        FaweProjectionIntegration integration = new FaweProjectionIntegration(tracker, task -> schedule(plugin, task));
        try {
            verifyCapabilities();
            for (World world : plugin.getServer().getWorlds()) {
                verifyNativeWorld(new BukkitWorld(world));
            }
            integration.start(plugin, WorldEdit.getInstance().getEventBus());
            return integration;
        } catch (RuntimeException | LinkageError exception) {
            integration.close();
            throw exception;
        }
    }

    public synchronized CompletionStage<Void> prepareReload(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("FAWE reload timeout must be positive");
        }
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("FAWE integration is closed"));
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
    public void close() {
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
        verifyNativeWorld(WorldWrapper.unwrap(event.getWorld()));
        FaweEditQuiescence.Session session = quiescence.admit();
        if (session == null) {
            event.setCancelled(true);
            return;
        }
        try {
            event.onSessionClose(session::close);
            Extent extent = event.getExtent();
            FaweAppliedChunkProcessor.Binding binding = new FaweAppliedChunkProcessor.Binding(
                    world.worldId(), world.generation(), invalidator, quiescence, session);
            Extent processed = extent.addPostProcessor(new FaweAppliedChunkProcessor(binding));
            if (processed != extent) {
                addExtentAllowance();
                event.setExtent(processed);
            }
        } catch (RuntimeException | Error failure) {
            session.close();
            throw failure;
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
        for (World world : plugin.getServer().getWorlds()) {
            rememberWorld(world);
        }
        addExtentAllowance();
        eventBus = bus;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        eventBus.register(this);
    }

    static void verifyCapabilities() {
        try {
            EditSessionEvent.class.getMethod("onSessionClose", Runnable.class);
            IBatchProcessor.class.getMethod("capturePostProcessor", IChunk.class);
            IBatchProcessor.class.getMethod("requiresBeforeState");
            IBatchProcessor.class.getMethod("applicationStarted");
            IBatchProcessor.class.getMethod("applicationCompleted");
            BukkitWorld.class.getMethod("appliedChangeSupportVersion");
            Class.forName("com.fastasyncworldedit.core.util.task.NativeChangeScope", false,
                    IBatchProcessor.class.getClassLoader());
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("FAWE projection tracking requires the patched FAWE 2.16.1 Wormholes build", exception);
        }
    }

    private static void verifyNativeWorld(Extent world) {
        if (!(world instanceof BukkitWorld bukkitWorld) || bukkitWorld.appliedChangeSupportVersion() != 1) {
            throw new IllegalStateException("FAWE native adapter does not support applied-change tracking for world "
                    + world + "; install the patched FAWE 2.16.1 Wormholes build");
        }
    }

    private static void schedule(Plugin plugin, Runnable task) {
        if (!FoliaScheduler.runGlobal(plugin, task, 1L)) {
            throw new IllegalStateException("Cannot schedule FAWE projection invalidation while Wormholes is unavailable");
        }
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
    private record WorldGeneration(UUID worldId, long generation) {
    }
}
