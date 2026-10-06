package art.arcane.wormholes.modded;

import art.arcane.wormholes.network.view.RemoteViewCodec;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

public final class MinecraftRemoteViewCodec implements RemoteViewCodec<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final MinecraftPacketBlobs blobs;

    public MinecraftRemoteViewCodec(RegistryAccess registries) {
        blobs = new MinecraftPacketBlobs(registries);
    }

    @Override
    public BlockState parseBlock(String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false).blockState();
        } catch (CommandSyntaxException error) {
            throw new IllegalArgumentException("Invalid remote block state", error);
        }
    }

    @Override
    public BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }

    @Override
    public BlockState occluded() {
        return MinecraftProjectorBlocks.INSTANCE.occluded();
    }

    @Override
    public BlockState[] palette(int size) {
        return new BlockState[size];
    }

    @Override
    public boolean blockEntityCandidate(String state) {
        int bracket = state.indexOf('[');
        Identifier key = Identifier.tryParse(bracket < 0 ? state : state.substring(0, bracket));
        Block block = key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
        return block != null && MinecraftProjectorBlocks.INSTANCE.blockEntityCandidate(block.defaultBlockState());
    }

    @Override
    public List<SynchedEntityData.DataValue<?>> metadata(byte[] data) {
        return blobs.readMetadata(data);
    }

    @Override
    public List<MinecraftPacketBlobs.Equipment> equipment(byte[] data) {
        return blobs.readEquipment(data);
    }

    @Override
    public void warning(String message, Throwable error) {
        if (error == null) {
            LOGGER.warn(message);
        } else {
            LOGGER.warn(message, error);
        }
    }

    @Override
    public void debug(Supplier<String> message) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(message.get());
        }
    }
}
