package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PocketResizeWorkflowTest {
    private static final PocketShell SOURCE = PocketShell.defaults();
    private static final PocketShell TARGET = new PocketShell(32, "OBSIDIAN", "OAK_DOOR");
    private static final PocketResizeWorkflow.Preflight READY = (source, target) -> {
    };

    @TempDir
    Path temporaryDirectory;

    @Test
    void journalSurvivesRestartAndOnlyCompletesTheExactIntent() throws Exception {
        Path directory = temporaryDirectory.resolve("journal");
        PocketSpace source = space(1L, SOURCE);
        PocketResizeJournal first = loadedJournal(directory);

        PocketResizeIntent intent = first.begin(source, TARGET);
        PocketResizeJournal restarted = loadedJournal(directory);

        assertEquals(List.of(intent), restarted.pending());
        assertThrows(IllegalStateException.class, () -> restarted.begin(source, TARGET));
        assertThrows(IllegalStateException.class, () -> restarted.complete(new PocketResizeIntent(
            UUID.randomUUID(), source.spaceId(), SOURCE, TARGET)));

        restarted.complete(intent);

        assertTrue(restarted.pending().isEmpty());
        assertTrue(loadedJournal(directory).pending().isEmpty());
    }

    @Test
    void journalsForDifferentPocketsCanProgressIndependently() throws Exception {
        PocketResizeJournal journal = loadedJournal(temporaryDirectory.resolve("journal"));
        PocketSpace first = space(2L, SOURCE);
        PocketSpace second = space(3L, SOURCE);

        PocketResizeIntent firstIntent = journal.begin(first, TARGET);
        PocketResizeIntent secondIntent = journal.begin(second, TARGET);

        journal.complete(secondIntent);

        assertEquals(List.of(firstIntent), journal.pending());
    }

    @Test
    void pocketQuarantineDoesNotBlockUnrelatedEntriesOrOverrideLifecycleDrain() {
        DoorStateGuard guard = new DoorStateGuard();
        UUID affected = new UUID(31L, 37L);
        UUID unrelated = new UUID(41L, 43L);

        guard.quarantinePocket(affected);
        assertTrue(guard.acceptingEntries());
        assertTrue(guard.pocketQuarantined(affected));
        assertFalse(guard.pocketQuarantined(unrelated));
        assertTrue(guard.beginDrain());
        assertFalse(guard.acceptingEntries());
        guard.resumeEntries();
        assertTrue(guard.acceptingEntries());
    }

    @Test
    void failedIntentKeepsOnlyItsPocketQuarantined() {
        DoorStateGuard guard = new DoorStateGuard();
        UUID affected = new UUID(41L, 43L);
        UUID unrelated = new UUID(47L, 53L);

        guard.quarantinePocket(affected);

        assertTrue(guard.acceptingEntries());
        assertTrue(guard.pocketQuarantined(affected));
        assertFalse(guard.pocketQuarantined(unrelated));

        guard.restorePocket(affected);
        assertFalse(guard.pocketQuarantined(affected));
    }

    @Test
    void scheduledResizeRejectsAStaleShellBeforeOpeningItsJournal() throws Exception {
        Path directory = temporaryDirectory.resolve("journal");
        PocketResizeJournal journal = loadedJournal(directory);
        PocketSpace scheduled = space(9L, SOURCE);
        PocketSpace changed = scheduled.withShell(new PocketShell(48, "STONE", "BIRCH_DOOR"));

        assertThrows(IllegalStateException.class,
            () -> PocketResizeWorkflow.validateScheduledSource(scheduled, changed));

        assertTrue(journal.pending().isEmpty());
    }

    @Test
    void unsupportedRegionFailsBeforeOpeningItsJournal() throws Exception {
        PocketResizeJournal journal = loadedJournal(temporaryDirectory.resolve("journal"));
        PocketResizeWorkflow workflow = new PocketResizeWorkflow(journal);
        PocketSpace source = space(14L, SOURCE);
        AtomicBoolean worldMutationStarted = new AtomicBoolean();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> workflow.execute(
            source,
            TARGET,
            new PocketResizeWorkflow.Actions(
                (operationSource, target) -> {
                    throw new IllegalStateException(
                        "pocket reshaping is unsupported unless one current region owns both room shells");
                },
                (operationSource, target) -> {
                    worldMutationStarted.set(true);
                    return CompletableFuture.completedFuture(null);
                },
                (current, target) -> current.withShell(target),
                (previous, updated) -> {
                },
                PocketResizeWorkflowTest::runImmediately
            )
        ));

        assertTrue(failure.getMessage().contains("one current region"));
        assertFalse(worldMutationStarted.get());
        assertTrue(journal.pending().isEmpty());
    }

    @Test
    void nonEmptyContainersAreRefusedEvenWhenDestructionIsConfirmed() {
        PocketResizeService.Impact containers = new PocketResizeService.Impact(3L, 2L, 0L, 0L);

        assertEquals(PocketResizePolicy.Decision.NON_EMPTY_CONTAINERS,
            PocketResizePolicy.decide(containers, false));
        assertEquals(PocketResizePolicy.Decision.NON_EMPTY_CONTAINERS,
            PocketResizePolicy.decide(containers, true));
        assertEquals(PocketResizeOutcome.Status.NON_EMPTY_CONTAINERS,
            PocketResizeOutcome.nonEmptyContainers(space(10L, SOURCE), TARGET, containers).status());
    }

    @Test
    void confirmationStillControlsDestructiveResizesWithoutStoredItems() {
        PocketResizeService.Impact destructive = new PocketResizeService.Impact(3L, 0L, 1L, 0L);

        assertEquals(PocketResizePolicy.Decision.NEEDS_CONFIRMATION,
            PocketResizePolicy.decide(destructive, false));
        assertEquals(PocketResizePolicy.Decision.PROCEED,
            PocketResizePolicy.decide(destructive, true));
        assertEquals(PocketResizePolicy.Decision.PROCEED,
            PocketResizePolicy.decide(PocketResizeService.Impact.none(), false));
    }

    @Test
    void workflowPersistsIntentBeforeWorldAndClearsItAfterPublication() throws Exception {
        PocketResizeJournal journal = loadedJournal(temporaryDirectory.resolve("journal"));
        PocketResizeWorkflow workflow = new PocketResizeWorkflow(journal);
        PocketSpace source = space(4L, SOURCE);
        List<String> order = new ArrayList<>();

        PocketSpace persisted = workflow.execute(source, TARGET, new PocketResizeWorkflow.Actions(
            READY,
            (operationSource, target) -> {
                assertTrue(journal.pending(source.spaceId()).isPresent());
                assertEquals(SOURCE, operationSource.shell());
                order.add("world");
                return CompletableFuture.completedFuture(null);
            },
            (current, target) -> {
                assertEquals(List.of("world"), order);
                assertTrue(journal.pending(source.spaceId()).isPresent());
                order.add("state");
                return current.withShell(target);
            },
            (previous, updated) -> {
                assertEquals(List.of("world", "state"), order);
                assertTrue(journal.pending(source.spaceId()).isPresent());
                order.add("publish");
            },
            PocketResizeWorkflowTest::runImmediately
        )).toCompletableFuture().join();

        assertEquals(source.withShell(TARGET), persisted);
        assertEquals(List.of("world", "state", "publish"), order);
        assertTrue(journal.pending().isEmpty());
    }

    @Test
    void stateFailureLeavesDurableIntentAndRecoveryReplaysFromSource() throws Exception {
        Path directory = temporaryDirectory.resolve("journal");
        PocketResizeJournal journal = loadedJournal(directory);
        PocketResizeWorkflow workflow = new PocketResizeWorkflow(journal);
        PocketSpace source = space(5L, SOURCE);

        CompletionException failure = assertThrows(CompletionException.class,
            () -> workflow.execute(source, TARGET,
            new PocketResizeWorkflow.Actions(
                READY,
                (operationSource, target) -> {
                    return CompletableFuture.completedFuture(null);
                },
                (current, target) -> {
                    throw new IOException("state unavailable");
                },
                (previous, updated) -> {
                },
                PocketResizeWorkflowTest::runImmediately
            )).toCompletableFuture().join());

        assertTrue(failure.getCause() instanceof IOException);

        PocketResizeJournal restarted = loadedJournal(directory);
        PocketResizeIntent intent = restarted.pending().getFirst();
        AtomicReference<PocketShell> replayedSource = new AtomicReference<>();
        PocketSpace recovered = new PocketResizeWorkflow(restarted).recover(intent, source,
            new PocketResizeWorkflow.Actions(
                READY,
                (operationSource, target) -> {
                    replayedSource.set(operationSource.shell());
                    return CompletableFuture.completedFuture(null);
                },
                (current, target) -> current.withShell(target),
                (previous, updated) -> {
                },
                PocketResizeWorkflowTest::runImmediately
            )).toCompletableFuture().join();

        assertEquals(SOURCE, replayedSource.get());
        assertEquals(TARGET, recovered.shell());
        assertTrue(restarted.pending().isEmpty());
    }

    @Test
    void publicationFailureRecoversAfterStateReachedTargetWithoutRepersisting() throws Exception {
        Path directory = temporaryDirectory.resolve("journal");
        PocketResizeJournal journal = loadedJournal(directory);
        PocketResizeWorkflow workflow = new PocketResizeWorkflow(journal);
        PocketSpace source = space(6L, SOURCE);

        CompletionException failure = assertThrows(CompletionException.class,
            () -> workflow.execute(source, TARGET,
            new PocketResizeWorkflow.Actions(
                READY,
                (operationSource, target) -> {
                    return CompletableFuture.completedFuture(null);
                },
                (current, target) -> current.withShell(target),
                (previous, updated) -> {
                    throw new IOException("endpoint unavailable");
                },
                PocketResizeWorkflowTest::runImmediately
            )).toCompletableFuture().join());

        assertTrue(failure.getCause() instanceof IOException);

        PocketResizeJournal restarted = loadedJournal(directory);
        PocketResizeIntent intent = restarted.pending().getFirst();
        AtomicReference<PocketShell> replayedSource = new AtomicReference<>();
        AtomicBoolean statePersisted = new AtomicBoolean();
        PocketSpace stateAtTarget = source.withShell(TARGET);
        PocketSpace recovered = new PocketResizeWorkflow(restarted).recover(intent, stateAtTarget,
            new PocketResizeWorkflow.Actions(
                READY,
                (operationSource, target) -> {
                    replayedSource.set(operationSource.shell());
                    return CompletableFuture.completedFuture(null);
                },
                (current, target) -> {
                    statePersisted.set(true);
                    return current.withShell(target);
                },
                (previous, updated) -> {
                },
                PocketResizeWorkflowTest::runImmediately
            )).toCompletableFuture().join();

        assertEquals(SOURCE, replayedSource.get());
        assertFalse(statePersisted.get());
        assertEquals(stateAtTarget, recovered);
        assertTrue(restarted.pending().isEmpty());
    }

    @Test
    void conflictingPersistedShellKeepsJournalForOperatorRecovery() throws Exception {
        Path directory = temporaryDirectory.resolve("journal");
        PocketSpace source = space(7L, SOURCE);
        PocketResizeJournal journal = loadedJournal(directory);
        journal.begin(source, TARGET);
        PocketResizeJournal restarted = loadedJournal(directory);
        PocketResizeIntent intent = restarted.pending().getFirst();
        PocketSpace conflicting = source.withShell(new PocketShell(48, "STONE", "BIRCH_DOOR"));

        assertThrows(IllegalStateException.class, () -> new PocketResizeWorkflow(restarted).recover(
            intent,
            conflicting,
            new PocketResizeWorkflow.Actions(
                READY,
                (operationSource, target) -> {
                    return CompletableFuture.completedFuture(null);
                },
                (current, target) -> current.withShell(target),
                (previous, updated) -> {
                },
                PocketResizeWorkflowTest::runImmediately
            )));

        assertEquals(List.of(intent), loadedJournal(directory).pending());
    }

    @Test
    void mismatchedJournalFilenameFailsClosed() throws Exception {
        Path directory = temporaryDirectory.resolve("journal");
        PocketResizeJournal journal = loadedJournal(directory);
        PocketResizeIntent intent = journal.begin(space(8L, SOURCE), TARGET);
        Path expected = directory.resolve(intent.spaceId() + ".json");
        Path mismatched = directory.resolve(UUID.randomUUID() + ".json");
        Files.move(expected, mismatched);

        IOException failure = assertThrows(IOException.class, () -> new PocketResizeJournal(directory).load());

        assertTrue(failure.getMessage().contains("filename does not match"));
    }

    @Test
    void workflowKeepsIntentUntilEveryWorldMutationCompletes() throws Exception {
        PocketResizeJournal journal = loadedJournal(temporaryDirectory.resolve("journal"));
        PocketResizeWorkflow workflow = new PocketResizeWorkflow(journal);
        PocketSpace source = space(11L, SOURCE);
        CompletableFuture<Void> worldMutation = new CompletableFuture<>();
        AtomicBoolean persisted = new AtomicBoolean();

        CompletionStage<PocketSpace> operation = workflow.execute(source, TARGET,
            new PocketResizeWorkflow.Actions(
                READY,
                (operationSource, target) -> worldMutation,
                (current, target) -> {
                    persisted.set(true);
                    return current.withShell(target);
                },
                (previous, updated) -> {
                },
                PocketResizeWorkflowTest::runImmediately
            ));

        assertTrue(journal.pending(source.spaceId()).isPresent());
        assertFalse(operation.toCompletableFuture().isDone());
        assertFalse(persisted.get());

        worldMutation.complete(null);

        assertEquals(source.withShell(TARGET), operation.toCompletableFuture().join());
        assertTrue(persisted.get());
        assertTrue(journal.pending().isEmpty());
    }

    @Test
    void failedWorldMutationKeepsIntentAndSkipsPersistence() throws Exception {
        PocketResizeJournal journal = loadedJournal(temporaryDirectory.resolve("journal"));
        PocketResizeWorkflow workflow = new PocketResizeWorkflow(journal);
        PocketSpace source = space(12L, SOURCE);
        CompletableFuture<Void> worldMutation = new CompletableFuture<>();
        AtomicBoolean persisted = new AtomicBoolean();
        CompletionStage<PocketSpace> operation = workflow.execute(source, TARGET,
            new PocketResizeWorkflow.Actions(
                READY,
                (operationSource, target) -> worldMutation,
                (current, target) -> {
                    persisted.set(true);
                    return current.withShell(target);
                },
                (previous, updated) -> {
                },
                PocketResizeWorkflowTest::runImmediately
            ));

        worldMutation.completeExceptionally(new IllegalStateException("entity teleport failed"));

        CompletionException failure = assertThrows(
            CompletionException.class,
            () -> operation.toCompletableFuture().join());
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertFalse(persisted.get());
        assertTrue(journal.pending(source.spaceId()).isPresent());
    }

    @Test
    void retiredRegionContinuationKeepsIntentAndSkipsPersistence() throws Exception {
        PocketResizeJournal journal = loadedJournal(temporaryDirectory.resolve("journal"));
        PocketResizeWorkflow workflow = new PocketResizeWorkflow(journal);
        PocketSpace source = space(13L, SOURCE);
        AtomicBoolean persisted = new AtomicBoolean();

        CompletionStage<PocketSpace> operation = workflow.execute(source, TARGET,
            new PocketResizeWorkflow.Actions(
                READY,
                (operationSource, target) -> CompletableFuture.completedFuture(null),
                (current, target) -> {
                    persisted.set(true);
                    return current.withShell(target);
                },
                (previous, updated) -> {
                },
                (task, retired) -> {
                    retired.run();
                    return true;
                }
            ));

        CompletionException failure = assertThrows(
            CompletionException.class,
            () -> operation.toCompletableFuture().join());
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertFalse(persisted.get());
        assertTrue(journal.pending(source.spaceId()).isPresent());
    }

    private static PocketResizeJournal loadedJournal(Path directory) throws IOException {
        PocketResizeJournal journal = new PocketResizeJournal(directory);
        journal.load();
        return journal;
    }

    private static boolean runImmediately(Runnable task, Runnable retired) {
        task.run();
        return true;
    }

    private static PocketSpace space(long value, PocketShell shell) {
        UUID spaceId = new UUID(0L, value);
        return new PocketSpace(
            spaceId,
            PocketBinding.personal(new UUID(1L, value)),
            value,
            Math.toIntExact(value * 256L),
            64,
            0,
            shell
        );
    }
}
