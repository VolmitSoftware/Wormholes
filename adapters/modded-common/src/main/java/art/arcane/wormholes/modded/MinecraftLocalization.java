package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.VolmitLocales;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.localization.WormholesLocaleLoader;
import art.arcane.wormholes.localization.WormholesMessageRenderer;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class MinecraftLocalization implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger("Wormholes");
    private static final Map<MinecraftServer, MinecraftLocalization> SERVICES = new ConcurrentHashMap<>();
    private final WormholesModRuntime runtime;
    private final MinecraftLanguageSwitcher switcher;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicLong generation = new AtomicLong();
    private volatile DefaultLanguage current;
    private volatile String fallbacks;
    private volatile boolean closed = true;
    private Path directory;
    private MinecraftServer server;
    private ExecutorService reader;
    private PluginLanguageService languages;
    private CompletableFuture<Void> defaultSelection = CompletableFuture.completedFuture(null);

    public MinecraftLocalization(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
        switcher = new MinecraftLanguageSwitcher(runtime, this);
    }

    public static MinecraftLocalization forPlayer(ServerPlayer player) {
        return Objects.requireNonNull(SERVICES.get(player.level().getServer()), "Wormholes localization is not running");
    }

    public static boolean chat(ServerPlayer player, String message) {
        MinecraftLocalization service = SERVICES.get(player.level().getServer());
        return service != null && service.switcher.editor().chat(player, message);
    }

    public static void menuOpened(ServerPlayer player, AbstractContainerMenu menu) {
        MinecraftLocalization service = SERVICES.get(player.level().getServer());
        if (service != null) {
            service.switcher.editor().opened(player, menu);
        }
    }

    public void start(Path directory, WormholesSettings settings) throws IOException {
        runtime.requireServerThread();
        this.directory = Objects.requireNonNull(directory);
        server = runtime.server();
        fallbacks = settings.getLanguageFallbacks();
        current = new DefaultLanguage(settings.getLanguage(), load(settings.getLanguage(), fallbacks));
        reader = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("Wormholes-languages").factory());
        closed = false;
        generation.incrementAndGet();
        languages = new PluginLanguageService(new PluginLanguageService.Options(
            directory.resolve("languages/language-preferences.properties"), VolmitLocales::all,
            () -> current.locale(), () -> current.snapshot(), locale -> load(locale, fallbacks),
            (locale, snapshot) -> current = new DefaultLanguage(locale, snapshot), LOGGER));
        switcher.editor().start(new PluginLanguageEditor(languages, new PluginLanguageEditor.Options(
            locale -> load(locale, locale.equals(VolmitLocales.ENGLISH) ? "" : fallbacks), edit -> {
                LocalizationSnapshot updated = WormholesLocaleLoader.edit(this.directory, edit,
                    edit.locale().equals(VolmitLocales.ENGLISH) ? "" : fallbacks);
                if (current.locale().equals(edit.locale())) {
                    current = new DefaultLanguage(edit.locale(), updated);
                }
                return updated;
            })));
        SERVICES.put(server, this);
    }

    public CompletableFuture<Void> reload(WormholesSettings settings) {
        runtime.requireServerThread();
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Wormholes localization is closed"));
        }
        long expected = generation.incrementAndGet();
        CompletableFuture<Void> result = track(new CompletableFuture<>());
        reader.execute(() -> prepareReload(settings, expected, result));
        return result;
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        closed = true;
        switcher.editor().close();
        generation.incrementAndGet();
        if (server != null) {
            SERVICES.remove(server, this);
        }
        if (reader != null) {
            reader.shutdownNow();
        }
        if (languages != null) {
            languages.close();
        }
        for (CompletableFuture<?> result : pending) {
            result.completeExceptionally(new CancellationException("Wormholes localization stopped"));
        }
        pending.clear();
    }

    public LocalizationSnapshot snapshot(ServerPlayer player) {
        return languages.snapshot(player == null ? null : player.getUUID());
    }

    public Component text(ServerPlayer player, TextKey key, Map<String, ?> arguments) {
        return MinecraftMenuText.text(snapshot(player), key, arguments);
    }

    CompletableFuture<String> selectPlayer(ServerPlayer player, String locale) {
        runtime.requireServerThread();
        UUID playerId = player.getUUID();
        return track(locale.equalsIgnoreCase("reset")
            ? languages.clearPlayer(playerId).thenApply(ignored -> "reset")
            : languages.selectPlayer(playerId, locale).thenApply(ignored -> languages.effectiveLocale(playerId)));
    }

    CompletableFuture<Void> selectServer(String locale) {
        runtime.requireServerThread();
        CompletableFuture<Void> result = track(new CompletableFuture<>());
        long expected = generation.get();
        defaultSelection.whenComplete((previous, failure) -> server.execute(() -> beginServerSelection(locale, expected, result)));
        defaultSelection = result;
        return result;
    }

    private void beginServerSelection(String locale, long expected, CompletableFuture<Void> result) {
        if (!active(expected, result)) {
            return;
        }
        DefaultLanguage before = current;
        languages.selectDefault(locale).whenComplete((ignored, failure) -> server.execute(() -> {
            if (!active(expected, result)) {
                return;
            }
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            DefaultLanguage selected = current;
            runtime.configuration().setLanguage(selected.locale()).whenComplete((saved, error) -> {
                if (error != null) {
                    if (current == selected) {
                        current = before;
                    }
                    result.completeExceptionally(error);
                } else {
                    result.complete(null);
                }
            });
        }));
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        switcher.register(dispatcher);
    }

    public void disconnected(ServerPlayer player) {
        switcher.editor().disconnected(player);
    }

    PluginLanguageService languages() {
        return languages;
    }

    boolean closed() {
        return closed;
    }

    String directorText(ServerPlayer player, TextKey key, MessageArgs arguments) {
        return ComponentText.component(WormholesMessageRenderer.render(snapshot(player).resolve(key, arguments))).plain();
    }

    private void prepareReload(WormholesSettings settings, long expected, CompletableFuture<Void> result) {
        try {
            LocalizationSnapshot loaded = load(settings.getLanguage(), settings.getLanguageFallbacks());
            server.execute(() -> {
                if (!active(expected, result)) {
                    return;
                }
                fallbacks = settings.getLanguageFallbacks();
                current = new DefaultLanguage(settings.getLanguage(), loaded);
                languages.invalidate();
                result.complete(null);
            });
        } catch (IOException | RuntimeException error) {
            result.completeExceptionally(error);
        }
    }

    private LocalizationSnapshot load(String locale, String fallbacks) throws IOException {
        return LocalizationSnapshot.create(WormholesLocaleLoader.load(directory, locale,
            VolmitLocales.ENGLISH.equals(locale) ? "" : fallbacks));
    }

    private boolean active(long expected, CompletableFuture<?> result) {
        if (closed || expected != generation.get()) {
            result.completeExceptionally(new CancellationException("Wormholes language selection was superseded"));
            return false;
        }
        return !result.isDone();
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> result) {
        pending.add(result);
        result.whenComplete((value, error) -> pending.remove(result));
        return result;
    }

    private record DefaultLanguage(String locale, LocalizationSnapshot snapshot) {
    }
}
