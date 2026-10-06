package art.arcane.optics.plate;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;

import art.arcane.optics.spi.OpticsScheduler;

public final class PlatePipeline<B, W> {
    static final int ASYNC_CELLS_PER_STEP = 8192;

    private final OpticsScheduler<?, W> scheduler;
    private final BiConsumer<String, Throwable> warnings;
    private final ConcurrentLinkedQueue<PlateCaptureJob<B, W, ?>> submitted;
    private final ArrayDeque<PlateCaptureJob<B, W, ?>> active;
    private final ViewPlateCache<B, W> cache;

    public PlatePipeline(long maxBytes, OpticsScheduler<?, W> scheduler, BiConsumer<String, Throwable> warnings) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
        this.submitted = new ConcurrentLinkedQueue<PlateCaptureJob<B, W, ?>>();
        this.active = new ArrayDeque<PlateCaptureJob<B, W, ?>>();
        this.cache = new ViewPlateCache<B, W>(maxBytes, this::schedule);
    }

    public ViewPlateCache<B, W> cache() {
        return cache;
    }

    public int queuedCaptures() {
        return active.size() + submitted.size();
    }

    public void tickCaptures(int chunkBudget, int urgentChunkBudget) {
        drainSubmitted();
        int budget = Math.max(1, chunkBudget);
        int urgentBudget = Math.max(1, urgentChunkBudget);
        int count = active.size();
        for (int i = 0; i < count; i++) {
            PlateCaptureJob<B, W, ?> job = active.pollFirst();
            if (!cache.isBuilding(job)) {
                job.abort();
                continue;
            }
            boolean urgent = job.urgent();
            int taken;
            try {
                taken = job.capture(urgent ? urgentBudget : budget);
            } catch (RuntimeException failure) {
                job.abort();
                cache.buildFailed(job);
                warnings.accept("capture failed for portal " + job.key().portalId(), failure);
                continue;
            }
            if (urgent) {
                urgentBudget = Math.max(0, urgentBudget - taken);
            } else {
                budget = Math.max(0, budget - taken);
            }
            switch (job.phase()) {
                case CAPTURED -> build(job);
                case FAILED -> cache.buildFailed(job);
                case CAPTURING -> active.addLast(job);
            }
        }
    }

    public void clear() {
        drainSubmitted();
        for (PlateCaptureJob<B, W, ?> job : active) {
            job.abort();
            cache.buildFailed(job);
        }
        active.clear();
        cache.clear();
    }

    private void schedule(ViewPlateBuilder.Job<B, W> job) {
        if (job instanceof PlateCaptureJob<B, W, ?> capture) {
            submitted.add(capture);
            return;
        }
        build(job);
    }

    private void build(ViewPlateBuilder.Job<B, W> job) {
        try {
            scheduler.compute().execute(() -> runToCompletion(job));
        } catch (RejectedExecutionException rejected) {
            cache.buildFailed(job);
        }
    }

    private void runToCompletion(ViewPlateBuilder.Job<B, W> job) {
        try {
            while (!job.step(ASYNC_CELLS_PER_STEP)) {
                if (Thread.currentThread().isInterrupted()) {
                    cache.buildFailed(job);
                    return;
                }
            }
            cache.publish(job, job.result());
        } catch (RuntimeException failure) {
            cache.buildFailed(job);
            warnings.accept("build failed for portal " + job.key().portalId(), failure);
        }
    }

    private void drainSubmitted() {
        PlateCaptureJob<B, W, ?> incoming = submitted.poll();
        while (incoming != null) {
            active.addLast(incoming);
            incoming = submitted.poll();
        }
    }
}
