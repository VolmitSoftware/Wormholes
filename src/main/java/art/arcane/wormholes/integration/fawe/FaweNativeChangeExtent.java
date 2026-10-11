package art.arcane.wormholes.integration.fawe;

import com.fastasyncworldedit.core.util.task.NativeChangeScope;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.OperationQueue;
import com.sk89q.worldedit.function.operation.RunContext;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BlockStateHolder;

final class FaweNativeChangeExtent extends AbstractDelegateExtent {
    private final FaweAppliedChunkProcessor processor;

    FaweNativeChangeExtent(Extent child, FaweAppliedChunkProcessor processor) {
        super(child);
        this.processor = processor;
    }

    @Override
    public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 position, B block) throws WorldEditException {
        try (NativeChangeScope scope = begin(position.x(), position.z())) {
            boolean changed = getExtent().setBlock(position, block);
            scope.complete(changed);
            return changed;
        }
    }

    @Override
    public <B extends BlockStateHolder<B>> boolean setBlock(int x, int y, int z, B block) throws WorldEditException {
        try (NativeChangeScope scope = begin(x, z)) {
            boolean changed = getExtent().setBlock(x, y, z, block);
            scope.complete(changed);
            return changed;
        }
    }

    @Override
    public boolean setBiome(BlockVector3 position, BiomeType biome) {
        try (NativeChangeScope scope = begin(position.x(), position.z())) {
            boolean changed = getExtent().setBiome(position, biome);
            scope.complete(changed);
            return changed;
        }
    }

    @Override
    public boolean setBiome(int x, int y, int z, BiomeType biome) {
        try (NativeChangeScope scope = begin(x, z)) {
            boolean changed = getExtent().setBiome(x, y, z, biome);
            scope.complete(changed);
            return changed;
        }
    }

    @Override
    public Operation commit() {
        Operation child = getExtent().commit();
        if (!(getExtent() instanceof World world)) {
            return child;
        }
        Operation completion = new NativeFlush(world);
        return child == null ? completion : new OperationQueue(child, completion);
    }

    private NativeChangeScope begin(int blockX, int blockZ) {
        processor.applicationStarted();
        return NativeChangeScope.open(() -> processor.applied(blockX, blockZ), processor::applicationCompleted);
    }

    private record NativeFlush(World world) implements Operation {
        @Override
        public Operation resume(RunContext run) {
            world.flush();
            return null;
        }

        @Override
        public void cancel() {
        }
    }
}
