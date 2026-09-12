package art.arcane.wormholes.util.project.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WormholesResourceWatcherTest {
    @TempDir
    Path directory;

    @Test
    void watchesLanguageCreationAtomicReplacementAndDeletion() throws Exception {
        Path language = dataFolder().resolve("languages/de_DE.toml");
        Files.createDirectories(language.getParent());
        LinkedBlockingQueue<Set<Path>> events = new LinkedBlockingQueue<>();
        try (WormholesResourceWatcher watcher = watcher(paths -> {
            events.add(paths);
            return CompletableFuture.completedFuture(true);
        })) {
            start(watcher, events);
            Files.writeString(language, "message = 'first'");
            assertTrue(next(events).contains(language));
            Path replacement = language.resolveSibling("replacement.tmp");
            Files.writeString(replacement, "message = 'replacement'");
            Files.move(replacement, language, StandardCopyOption.REPLACE_EXISTING);
            assertTrue(next(events).contains(language));
            Files.delete(language);
            assertTrue(next(events).contains(language));
        }
    }

    @Test
    void watchesNewMetricsDirectoryAndSameMetadataEdits() throws Exception {
        Path language = dataFolder().resolve("languages/en_US.toml");
        Files.createDirectories(language.getParent());
        Files.writeString(language, "message = 'first'");
        FileTime timestamp = Files.getLastModifiedTime(language);
        LinkedBlockingQueue<Set<Path>> events = new LinkedBlockingQueue<>();
        try (WormholesResourceWatcher watcher = watcher(paths -> {
            events.add(paths);
            return CompletableFuture.completedFuture(true);
        })) {
            start(watcher, events);
            Files.writeString(language, "message = 'other'");
            Files.setLastModifiedTime(language, timestamp);
            assertTrue(next(events).contains(language));
            Path metrics = dataFolder().resolveSibling("bStats/config.yml");
            Files.createDirectories(metrics.getParent());
            Files.writeString(metrics, "enabled: false\n");
            assertTrue(next(events).contains(metrics));
        }
    }

    @Test
    void retainsEditsMadeWhileAnApplicationIsPending() throws Exception {
        Path language = dataFolder().resolve("languages/en_US.toml");
        Files.createDirectories(language.getParent());
        Files.writeString(language, "message = 'initial'");
        LinkedBlockingQueue<Set<Path>> events = new LinkedBlockingQueue<>();
        CompletableFuture<Boolean> first = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean changing = new AtomicBoolean();
        try (WormholesResourceWatcher watcher = watcher(paths -> {
            events.add(paths);
            if (!changing.get()) {
                return CompletableFuture.completedFuture(true);
            }
            return calls.incrementAndGet() == 1 ? first : CompletableFuture.completedFuture(true);
        })) {
            start(watcher, events);
            changing.set(true);
            Files.writeString(language, "message = 'first'");
            next(events);
            Files.writeString(language, "message = 'newer'");
            assertNull(events.poll(600L, TimeUnit.MILLISECONDS));
            first.complete(true);
            assertTrue(next(events).contains(language));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void retriesRejectedApplicationsAndIgnoresUnmanagedFiles() throws Exception {
        Path language = dataFolder().resolve("languages/en_US.toml");
        Files.createDirectories(language.getParent());
        Files.writeString(language, "message = 'initial'");
        LinkedBlockingQueue<Set<Path>> events = new LinkedBlockingQueue<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean changing = new AtomicBoolean();
        try (WormholesResourceWatcher watcher = watcher(paths -> {
            events.add(paths);
            if (!changing.get()) {
                return CompletableFuture.completedFuture(true);
            }
            return CompletableFuture.completedFuture(calls.incrementAndGet() > 1);
        })) {
            start(watcher, events);
            changing.set(true);
            Files.writeString(language.resolveSibling("language-preferences.properties"), "player=de_DE");
            assertNull(events.poll(700L, TimeUnit.MILLISECONDS));
            Files.writeString(language, "message = 'updated'");
            next(events);
            assertTrue(next(events).contains(language));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void stoppedWatcherDoesNotDispatchLaterChanges() throws Exception {
        Path language = dataFolder().resolve("languages/en_US.toml");
        Files.createDirectories(language.getParent());
        LinkedBlockingQueue<Set<Path>> events = new LinkedBlockingQueue<>();
        WormholesResourceWatcher watcher = watcher(paths -> {
            events.add(paths);
            return CompletableFuture.completedFuture(true);
        });
        watcher.start();
        watcher.close();
        Files.writeString(language, "message = 'updated'");
        assertNull(events.poll(700L, TimeUnit.MILLISECONDS));
    }

    private Set<Path> next(LinkedBlockingQueue<Set<Path>> events) throws InterruptedException {
        Set<Path> result = events.poll(7L, TimeUnit.SECONDS);
        assertNotNull(result, "The resource edit must be applied without touching wormholes.toml");
        return result;
    }

    @Test
    void metricsFailuresDoNotBlockLanguageEdits() throws Exception {
        Path language = dataFolder().resolve("languages/en_US.toml");
        Files.createDirectories(language.getParent());
        Files.writeString(language, "message = 'initial'");
        LinkedBlockingQueue<Set<Path>> events = new LinkedBlockingQueue<>();
        try (WormholesResourceWatcher watcher = watcher(paths -> {
            if (paths.stream().anyMatch(path -> path.getFileName().toString().equals("config.yml"))) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid metrics configuration"));
            }
            events.add(paths);
            return CompletableFuture.completedFuture(true);
        })) {
            watcher.start();
            next(events);
            Files.writeString(language, "message = 'updated'");
            assertTrue(next(events).contains(language));
        }
    }

    private void start(WormholesResourceWatcher watcher, LinkedBlockingQueue<Set<Path>> events) throws Exception {
        watcher.start();
        next(events);
        next(events);
        assertNull(events.poll(500L, TimeUnit.MILLISECONDS));
    }

    private Path dataFolder() {
        return directory.resolve("plugins/Wormholes");
    }

    private WormholesResourceWatcher watcher(Function<Set<Path>, CompletableFuture<Boolean>> reload) {
        return new WormholesResourceWatcher(new WormholesResourceWatcher.Options(
            dataFolder(), Logger.getLogger(getClass().getName()), reload));
    }
}
