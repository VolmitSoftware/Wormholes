package art.arcane.wormholes.modded;

import art.arcane.wormholes.network.replication.capture.CaptureAccess;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MinecraftCaptureAccess implements CaptureAccess<ServerLevel, BlockState> {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final Map<UUID, ServerLevel> worlds = new HashMap<>();
    private final Map<ServerLevel, UUID> worldIds = new HashMap<>();
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();

    @Override
    public UUID worldId(ServerLevel world) {
        return worldIds.computeIfAbsent(world, level -> {
            UUID id = UUID.nameUUIDFromBytes(level.dimension().identifier().toString().getBytes(StandardCharsets.UTF_8));
            worlds.put(id, level);
            return id;
        });
    }

    @Override
    public ServerLevel world(UUID id) {
        return worlds.get(id);
    }

    @Override
    public int minHeight(ServerLevel world) {
        return world.getMinY();
    }

    @Override
    public int maxHeight(ServerLevel world) {
        return world.getMaxY();
    }

    @Override
    public BlockState block(ServerLevel world, int x, int y, int z) {
        LevelChunk chunk = world.getChunkSource().getChunkNow(x >> 4, z >> 4);
        return chunk == null ? null : chunk.getBlockState(position.set(x, y, z));
    }

    @Override
    public boolean occluding(BlockState block) {
        return MinecraftProjectorBlocks.INSTANCE.isOccluding(block);
    }

    @Override
    public String blockKey(BlockState block) {
        return BlockStateParser.serialize(block);
    }

    @Override
    public BlockState copy(BlockState block) {
        return block;
    }

    @Override
    public boolean debugEnabled() {
        return LOGGER.isDebugEnabled();
    }

    @Override
    public void debug(String message) {
        LOGGER.debug(message);
    }
}
