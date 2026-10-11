package art.arcane.wormholes.integration.fawe;

import com.fastasyncworldedit.core.extent.processor.ProcessorScope;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.sk89q.worldedit.extent.Extent;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

final class FaweAppliedChunkProcessor implements IBatchProcessor {
    private final UUID worldId;
    private final long generation;
    private final FaweEditQuiescence quiescence;
    private final FaweEditQuiescence.Session session;
    private final FaweAppliedChunkInvalidator invalidator;

    FaweAppliedChunkProcessor(UUID worldId, FaweAppliedChunkInvalidator invalidator) {
        this(new Binding(worldId, 0L, invalidator, new FaweEditQuiescence(), null));
    }

    FaweAppliedChunkProcessor(Binding binding) {
        this.worldId = Objects.requireNonNull(binding.worldId());
        this.generation = binding.generation();
        this.invalidator = Objects.requireNonNull(binding.invalidator());
        this.quiescence = Objects.requireNonNull(binding.quiescence());
        this.session = binding.session();
    }

    @Override
    public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) {
        return set;
    }

    @Override
    public void postProcess(IChunk chunk, IChunkGet get, IChunkSet set) {
        if (set != null) {
            invalidator.applied(worldId, generation, chunk.getX(), chunk.getZ());
        }
    }

    @Override
    public Future<?> postProcessSet(IChunk chunk, IChunkGet get, IChunkSet set) {
        postProcess(chunk, get, set);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public IBatchProcessor capturePostProcessor(IChunk chunk) {
        return new AppliedCompletion(chunk.getX(), chunk.getZ());
    }

    @Override
    public boolean requiresBeforeState() {
        return false;
    }

    @Override
    public void applicationStarted() {
        quiescence.applicationStarted(session);
    }

    @Override
    public void applicationCompleted() {
        quiescence.applicationCompleted();
    }

    @Override
    public Extent construct(Extent child) {
        return new FaweNativeChangeExtent(child, this);
    }

    @Override
    public ProcessorScope getScope() {
        return ProcessorScope.READING_BLOCKS;
    }

    void applied(int blockX, int blockZ) {
        invalidator.applied(worldId, generation, blockX >> 4, blockZ >> 4);
    }

    record Binding(UUID worldId, long generation, FaweAppliedChunkInvalidator invalidator,
                   FaweEditQuiescence quiescence, FaweEditQuiescence.Session session) {
    }

    private final class AppliedCompletion implements IBatchProcessor {
        private final int chunkX;
        private final int chunkZ;

        private AppliedCompletion(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override
        public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) {
            return set;
        }

        @Override
        public void postProcess(IChunk chunk, IChunkGet get, IChunkSet set) {
            if (set != null) {
                invalidator.applied(worldId, generation, chunkX, chunkZ);
            }
        }

        @Override
        public boolean requiresBeforeState() {
            return false;
        }

        @Override
        public void applicationStarted() {
            FaweAppliedChunkProcessor.this.applicationStarted();
        }

        @Override
        public void applicationCompleted() {
            FaweAppliedChunkProcessor.this.applicationCompleted();
        }

        @Override
        public Extent construct(Extent child) {
            return FaweAppliedChunkProcessor.this.construct(child);
        }

        @Override
        public ProcessorScope getScope() {
            return ProcessorScope.READING_BLOCKS;
        }
    }
}
