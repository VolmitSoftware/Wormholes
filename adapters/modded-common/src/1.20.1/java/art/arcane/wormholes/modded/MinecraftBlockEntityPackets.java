package art.arcane.wormholes.modded;

import art.arcane.optics.math.CellKeys;
import art.arcane.optics.fidelity.BlockEntitySample;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

public final class MinecraftBlockEntityPackets {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final WormholesModRuntime runtime;
    private boolean failureLogged;

    public MinecraftBlockEntityPackets(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public void send(ServerPlayer observer, long key, BlockEntitySample sample) {
        runtime.requireServerThread();
        int x = CellKeys.unpackX(key);
        int z = CellKeys.unpackZ(key);
        if (observer.hasDisconnected()
            || !observer.serverLevel().getChunkSource().chunkMap.getPlayers(new ChunkPos(x >> 4, z >> 4), false).contains(observer)) {
            return;
        }
        try {
            observer.connection.send(packet(observer.serverLevel().registryAccess(), key, sample));
        } catch (IOException | RuntimeException failure) {
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.error("Wormholes block entity projection packet failed for {}", sample.typeKey(), failure);
            }
        }
    }

    static ClientboundBlockEntityDataPacket packet(RegistryAccess registries, long key, BlockEntitySample sample) throws IOException {
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(new ResourceLocation(sample.typeKey()))
            .orElseThrow(() -> new IOException("Unknown projected block entity type " + sample.typeKey()));
        CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(sample.nbt())));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeBlockPos(new BlockPos(CellKeys.unpackX(key), CellKeys.unpackY(key), CellKeys.unpackZ(key)));
            buffer.writeId(BuiltInRegistries.BLOCK_ENTITY_TYPE, type);
            buffer.writeNbt(tag);
            return new ClientboundBlockEntityDataPacket(buffer);
        } finally {
            buffer.release();
        }
    }
}
