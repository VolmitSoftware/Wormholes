package art.arcane.wormholes.door;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class PocketResizeWorkflow {
    private final PocketResizeJournal journal;

    PocketResizeWorkflow(PocketResizeJournal journal) {
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    static PocketSpace validateScheduledSource(PocketSpace scheduled, PocketSpace current) {
        PocketSpace requiredScheduled = Objects.requireNonNull(scheduled, "scheduled");
        PocketSpace requiredCurrent = Objects.requireNonNull(current, "current");
        if (!requiredScheduled.spaceId().equals(requiredCurrent.spaceId())
            || !requiredScheduled.shell().equals(requiredCurrent.shell())) {
            throw new IllegalStateException("pocket changed while its resize chunks were loading");
        }
        return requiredCurrent;
    }

    CompletionStage<PocketSpace> execute(
        PocketSpace current,
        PocketShell target,
        Actions actions
    ) throws IOException {
        Objects.requireNonNull(actions, "actions").preflight().validate(current, target);
        PocketResizeIntent intent = journal.begin(current, target);
        return recover(intent, current, actions);
    }

    CompletionStage<PocketSpace> recover(
        PocketResizeIntent intent,
        PocketSpace current,
        Actions actions
    ) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(actions, "actions");
        PocketSpace operationSource = intent.operationSource(current);
        CompletionStage<Void> worldMutation = Objects.requireNonNull(
            actions.worldMutation().apply(operationSource, intent.target()),
            "worldMutation result"
        );
        CompletableFuture<PocketSpace> result = new CompletableFuture<>();
        worldMutation.whenComplete((ignored, worldFailure) -> {
            Runnable continuation = () -> complete(intent, current, actions, worldFailure, result);
            Runnable retired = () -> result.completeExceptionally(
                new IllegalStateException("pocket resize continuation scheduler retired the operation"));
            try {
                if (!actions.continuation().schedule(continuation, retired)) {
                    retired.run();
                }
            } catch (RuntimeException exception) {
                result.completeExceptionally(
                    new IllegalStateException("pocket resize continuation scheduler failed", exception));
            }
        });
        return result;
    }

    private void complete(
        PocketResizeIntent intent,
        PocketSpace current,
        Actions actions,
        Throwable worldFailure,
        CompletableFuture<PocketSpace> result
    ) {
        if (worldFailure != null) {
            result.completeExceptionally(worldFailure);
            return;
        }
        try {
            result.complete(persist(intent, current, actions));
        } catch (IOException | RuntimeException exception) {
            result.completeExceptionally(exception);
        }
    }

    private PocketSpace persist(
        PocketResizeIntent intent,
        PocketSpace current,
        Actions actions
    ) throws IOException {
        PocketSpace persisted = current.shell().equals(intent.target())
            ? current
            : actions.stateMutation().apply(current, intent.target());
        if (!current.withShell(intent.target()).equals(persisted)) {
            throw new IllegalStateException("pocket resize persisted an unexpected result for " + current.spaceId());
        }
        actions.publication().apply(current, persisted);
        journal.complete(intent);
        return persisted;
    }

    record Actions(
        Preflight preflight,
        WorldMutation worldMutation,
        StateMutation stateMutation,
        Publication publication,
        Continuation continuation
    ) {
        Actions {
            Objects.requireNonNull(preflight, "preflight");
            Objects.requireNonNull(worldMutation, "worldMutation");
            Objects.requireNonNull(stateMutation, "stateMutation");
            Objects.requireNonNull(publication, "publication");
            Objects.requireNonNull(continuation, "continuation");
        }
    }

    @FunctionalInterface
    interface Preflight {
        void validate(PocketSpace source, PocketShell target);
    }

    @FunctionalInterface
    interface WorldMutation {
        CompletionStage<Void> apply(PocketSpace source, PocketShell target);
    }

    @FunctionalInterface
    interface StateMutation {
        PocketSpace apply(PocketSpace current, PocketShell target) throws IOException;
    }

    @FunctionalInterface
    interface Publication {
        void apply(PocketSpace previous, PocketSpace updated) throws IOException;
    }

    @FunctionalInterface
    interface Continuation {
        boolean schedule(Runnable task, Runnable retired);
    }
}
