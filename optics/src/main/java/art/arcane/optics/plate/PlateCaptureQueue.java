package art.arcane.optics.plate;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class PlateCaptureQueue<B, W> {
    public interface Host<B, W> {
        void build(ViewPlateBuilder.Job<B, W> job);

        void failed(ViewPlateBuilder.Job<B, W> job);

        void warning(ViewPlateKey key, RuntimeException failure);

        boolean wanted(ViewPlateBuilder.Job<B, W> job);
    }

    private final Host<B, W> host;
    private final ConcurrentLinkedQueue<PlateCaptureJob<B, W, ?>> submitted;
    private final ArrayDeque<PlateCaptureJob<B, W, ?>> active;

    public PlateCaptureQueue(Host<B, W> host) {
        this.host = host;
        this.submitted = new ConcurrentLinkedQueue<PlateCaptureJob<B, W, ?>>();
        this.active = new ArrayDeque<PlateCaptureJob<B, W, ?>>();
    }

    public void submit(PlateCaptureJob<B, W, ?> job) {
        submitted.add(job);
    }

    public int size() {
        return active.size() + submitted.size();
    }

    public void tick(int chunkBudget, int urgentChunkBudget) {
        PlateCaptureJob<B, W, ?> incoming = submitted.poll();
        while (incoming != null) {
            active.addLast(incoming);
            incoming = submitted.poll();
        }
        int budget = Math.max(1, chunkBudget);
        int urgentBudget = Math.max(1, urgentChunkBudget);
        int count = active.size();
        for (int i = 0; i < count; i++) {
            PlateCaptureJob<B, W, ?> job = active.pollFirst();
            if (!host.wanted(job)) {
                job.abort();
                continue;
            }
            boolean urgent = job.urgent();
            int taken;
            try {
                taken = job.capture(urgent ? urgentBudget : budget);
            } catch (RuntimeException failure) {
                job.abort();
                host.failed(job);
                host.warning(job.key(), failure);
                continue;
            }
            if (urgent) {
                urgentBudget = Math.max(0, urgentBudget - taken);
            } else {
                budget = Math.max(0, budget - taken);
            }
            switch (job.phase()) {
                case CAPTURED -> host.build(job);
                case FAILED -> host.failed(job);
                case CAPTURING -> active.addLast(job);
            }
        }
    }

    public void clear() {
        PlateCaptureJob<B, W, ?> incoming = submitted.poll();
        while (incoming != null) {
            active.addLast(incoming);
            incoming = submitted.poll();
        }
        for (PlateCaptureJob<B, W, ?> job : active) {
            job.abort();
            host.failed(job);
        }
        active.clear();
    }
}
