package art.arcane.wormholes;

import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.util.project.config.HotloadManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class WormholesReloadCoordinatorTest {
    @TempDir
    Path dataFolder;

    private WormholesSettings previousSettings;
    private WormholesLocalization previousLocalization;
    private Wormholes plugin;
    private WormholesDiagnosticsRuntime diagnostics;
    private WormholesReloadCoordinator coordinator;

    @BeforeEach
    void prepare() throws IOException {
        previousSettings = Wormholes.settings;
        previousLocalization = Wormholes.localization;
        plugin = mock(Wormholes.class);
        diagnostics = mock(WormholesDiagnosticsRuntime.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        coordinator = new WormholesReloadCoordinator(plugin, mock(WormholesDoorLifecycle.class),
            mock(WormholesNetworkRuntime.class), diagnostics);
        Wormholes.settings = settings(false);
        Wormholes.localization = new WormholesLocalization();
        Wormholes.localization.install(snapshot("Initial translation"));
    }

    @AfterEach
    void restore() {
        Wormholes.settings = previousSettings;
        Wormholes.localization = previousLocalization;
    }

    @Test
    void queuedConfigurationRetriesWhenANewerLanguageSnapshotHasApplied() throws ReflectiveOperationException, IOException {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        CompletableFuture<Boolean> applied = new CompletableFuture<>();
        WormholesSettings initialSettings = Wormholes.settings;
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runGlobal(eq(plugin), any(Runnable.class), eq(1L)))
                .thenAnswer(invocation -> {
                    queued.set(invocation.getArgument(1, Runnable.class));
                    return true;
                });
            assertTrue(scheduleConfiguration(settings(true), (success, failure) -> {
                if (failure == null) {
                    applied.complete(success);
                } else {
                    applied.completeExceptionally(failure);
                }
            }));
            LocalizationSnapshot latest = snapshot("Latest translation");
            Wormholes.localization.install(latest);

            queued.get().run();

            assertTrue(applied.isDone());
            assertFalse(applied.join());
            assertSame(initialSettings, Wormholes.settings);
            assertSame(latest, Wormholes.localization.defaultSnapshot());
            verifyNoInteractions(diagnostics);
        }
    }

    @Test
    void staleLanguageCommitRetriesAndFreshPreparationApplies() throws ReflectiveOperationException, IOException {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        WormholesLocalization active = Wormholes.localization;
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runGlobal(eq(plugin), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    queued.set(invocation.getArgument(1, Runnable.class));
                    return true;
                });
            CompletableFuture<?> stale = scheduleLanguage();
            LocalizationSnapshot latest = snapshot("Latest translation");
            active.install(latest);

            queued.get().run();

            assertTrue(stale.isDone());
            assertEquals(Boolean.FALSE, stale.join());
            assertSame(latest, active.defaultSnapshot());
            CompletableFuture<?> retry = scheduleLanguage();

            queued.get().run();

            assertTrue(retry.isDone());
            assertEquals(Boolean.TRUE, retry.join());
            assertSame(active, Wormholes.localization);
            assertEquals("Latest translation", active.defaultSnapshot()
                .resolve(WormholesMessages.COMMAND_UNKNOWN, MessageArgs.empty()).template());
            verifyNoInteractions(diagnostics);
        }
    }

    private boolean scheduleConfiguration(WormholesSettings settings, HotloadManager.ReloadCompletion completion)
        throws ReflectiveOperationException {
        Method callback = WormholesReloadCoordinator.class.getDeclaredMethod("onConfigHotReload", long.class,
            WormholesSettings.class, HotloadManager.ReloadCompletion.class);
        callback.setAccessible(true);
        return (Boolean) callback.invoke(coordinator, 0L, settings, completion);
    }

    private CompletableFuture<?> scheduleLanguage() throws ReflectiveOperationException {
        Method callback = WormholesReloadCoordinator.class.getDeclaredMethod("onResourceHotReload", long.class, Set.class);
        callback.setAccessible(true);
        return (CompletableFuture<?>) callback.invoke(coordinator, 0L,
            Set.of(dataFolder.resolve("languages/en_us.toml").toAbsolutePath().normalize()));
    }

    private LocalizationSnapshot snapshot(String message) throws IOException {
        Path languages = dataFolder.resolve("languages");
        Files.createDirectories(languages);
        Files.writeString(languages.resolve("en_us.toml"), "[command]\nunknown = \"" + message + "\"\n");
        WormholesLocalization prepared = new WormholesLocalization();
        assertTrue(prepared.reload(dataFolder, "en_us", "").applied());
        return prepared.defaultSnapshot();
    }

    private WormholesSettings settings(boolean metrics) {
        return WormholesSettings.loadSnapshot(("schema = 3\nmetrics = " + metrics + "\n")
            .getBytes(StandardCharsets.UTF_8));
    }
}
