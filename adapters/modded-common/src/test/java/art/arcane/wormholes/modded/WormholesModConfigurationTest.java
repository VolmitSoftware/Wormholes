package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WormholesModConfigurationTest {
    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void appliesValidatedReloadOnlyWhenServerThreadRunsIt() throws Exception {
        BlockingQueue<Runnable> pending = new LinkedBlockingQueue<>();
        try (WormholesModConfiguration configuration = configuration(pending)) {
            WormholesSettings original = configuration.settings();
            Files.writeString(configFile(), "schema = 3\nlanguage = \"JA_jp\"\n[main]\n"
                + "chunk-pre-send-enabled = true\nchunk-pre-send-radius-chunks = 999\n"
                + "chunk-pre-send-max-chunks = 99999\nchunk-pre-send-budget-micros = 999999\n");
            CompletableFuture<WormholesSettings> result = configuration.reload();
            Runnable apply = pending.poll(5L, TimeUnit.SECONDS);
            assertNotNull(apply);
            assertSame(original, configuration.settings());
            assertFalse(result.isDone());
            apply.run();
            assertSame(configuration.settings(), result.get(5L, TimeUnit.SECONDS));
            assertEquals("ja-JP", configuration.settings().getLanguage());
            assertTrue(configuration.preSendOptions().enabled());
            assertEquals(16, configuration.preSendOptions().radiusChunks());
            assertEquals(1024, configuration.preSendOptions().maxChunks());
            assertEquals(25_000_000L, configuration.preSendOptions().budgetNanos());
        }
    }

    @Test
    public void invalidReloadRetainsCurrentSettingsAndSourceBytes() throws Exception {
        BlockingQueue<Runnable> pending = new LinkedBlockingQueue<>();
        try (WormholesModConfiguration configuration = configuration(pending)) {
            WormholesSettings original = configuration.settings();
            String invalid = "schema = 3\nquality = \"invalid\"\n";
            Files.writeString(configFile(), invalid);
            CompletableFuture<WormholesSettings> result = configuration.reload();
            Runnable apply = pending.poll(5L, TimeUnit.SECONDS);
            assertNotNull(apply);
            apply.run();
            assertTrue(result.isCompletedExceptionally());
            assertSame(original, configuration.settings());
            assertEquals(invalid, Files.readString(configFile()));
        }
    }

    @Test
    public void shutdownCancelsReloadBeforeQueuedApplication() throws Exception {
        BlockingQueue<Runnable> pending = new LinkedBlockingQueue<>();
        WormholesModConfiguration configuration = configuration(pending);
        try {
            WormholesSettings original = configuration.settings();
            Files.writeString(configFile(), "schema = 3\nmetrics = false\n");
            CompletableFuture<WormholesSettings> result = configuration.reload();
            Runnable apply = pending.poll(5L, TimeUnit.SECONDS);
            assertNotNull(apply);
            configuration.close();
            assertThrows(CancellationException.class, result::join);
            apply.run();
            assertSame(original, configuration.settings());
            assertTrue(configuration.reload().isCompletedExceptionally());
        } finally {
            configuration.close();
        }
    }

    @Test
    public void persistenceCapturesCurrentSettingsBeforeQueuedReload() throws Exception {
        BlockingQueue<Runnable> pending = new LinkedBlockingQueue<>();
        try (WormholesModConfiguration configuration = configuration(pending)) {
            configuration.settings().getNetwork().enabled = true;
            CompletableFuture<Void> saved = configuration.persist();
            configuration.settings().getNetwork().enabled = false;
            Runnable completed = pending.poll(5L, TimeUnit.SECONDS);
            assertNotNull(completed);
            assertFalse(saved.isDone());
            completed.run();
            saved.get(5L, TimeUnit.SECONDS);
            assertTrue(WormholesSettings.loadSnapshot(Files.readAllBytes(configFile())).getNetwork().enabled);
            CompletableFuture<WormholesSettings> reloaded = configuration.reload();
            Runnable apply = pending.poll(5L, TimeUnit.SECONDS);
            assertNotNull(apply);
            apply.run();
            assertTrue(reloaded.get(5L, TimeUnit.SECONDS).getNetwork().enabled);
        }
    }

    @Test
    public void failedPersistenceReportsErrorAndRemovesTemporaryFile() throws Exception {
        BlockingQueue<Runnable> pending = new LinkedBlockingQueue<>();
        try (WormholesModConfiguration configuration = configuration(pending)) {
            Files.delete(configFile());
            Files.createDirectory(configFile());
            Files.writeString(configFile().resolve("occupied"), "retain");
            CompletableFuture<Void> saved = configuration.persist();
            Runnable completed = pending.poll(5L, TimeUnit.SECONDS);
            assertNotNull(completed);
            completed.run();
            assertTrue(saved.isCompletedExceptionally());
            assertEquals("retain", Files.readString(configFile().resolve("occupied")));
            try (Stream<Path> paths = Files.list(directory.getRoot().toPath())) {
                assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".wormholes-")));
            }
        }
    }

    @Test
    public void failedLanguageSelectionRollsBackWithoutLosingOtherSettings() throws Exception {
        BlockingQueue<Runnable> pending = new LinkedBlockingQueue<>();
        try (WormholesModConfiguration configuration = configuration(pending)) {
            String previous = configuration.settings().getLanguage();
            Files.delete(configFile());
            Files.createDirectory(configFile());
            Files.writeString(configFile().resolve("occupied"), "retain");
            CompletableFuture<Void> saved = configuration.setLanguage("ja-JP");
            configuration.settings().getNetwork().enabled = true;
            Runnable completed = pending.poll(5L, TimeUnit.SECONDS);
            assertNotNull(completed);
            completed.run();
            assertTrue(saved.isCompletedExceptionally());
            assertEquals(previous, configuration.settings().getLanguage());
            assertTrue(configuration.settings().getNetwork().enabled);
        }
    }

    private WormholesModConfiguration configuration(BlockingQueue<Runnable> pending) {
        Thread owner = Thread.currentThread();
        return new WormholesModConfiguration(directory.getRoot().toPath(),
            new WormholesModConfiguration.Execution(pending::add, () -> {
                if (Thread.currentThread() != owner) {
                    throw new IllegalStateException("Configuration applied off the server thread");
                }
            }));
    }

    private Path configFile() {
        return directory.getRoot().toPath().resolve(WormholesSettings.CONFIG_FILE_NAME);
    }
}
