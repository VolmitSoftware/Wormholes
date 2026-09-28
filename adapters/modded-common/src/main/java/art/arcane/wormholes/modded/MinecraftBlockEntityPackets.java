package art.arcane.wormholes.modded;

import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.blockentity.ProjectedBlockEntityLayer;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

public final class MinecraftBlockEntityPackets implements ProjectedBlockEntityLayer.PacketSink<ServerPlayer> {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final WormholesModRuntime runtime;
    private boolean failureLogged;

    public MinecraftBlockEntityPackets(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void send(ServerPlayer observer, long key, BlockEntitySample sample) {
        runtime.requireServerThread();
        int x = ProjectionCellKey.unpackX(key);
        int z = ProjectionCellKey.unpackZ(key);
        if (observer.hasDisconnected() || !observer.level().getChunkSource().chunkMap.isChunkTracked(observer, x >> 4, z >> 4)) {
            return;
        }
        try {
            observer.connection.send(packet(observer.level().registryAccess(), key, sample));
        } catch (IOException | RuntimeException failure) {
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.error("Wormholes block entity projection packet failed for {}", sample.typeKey(), failure);
            }
        }
    }

    static ClientboundBlockEntityDataPacket packet(RegistryAccess registries, long key, BlockEntitySample sample) throws IOException {
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(Identifier.parse(sample.typeKey()))
            .orElseThrow(() -> new IOException("Unknown projected block entity type " + sample.typeKey()));
        CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(sample.nbt())));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            BlockPos.STREAM_CODEC.encode(buffer, new BlockPos(ProjectionCellKey.unpackX(key), ProjectionCellKey.unpackY(key), ProjectionCellKey.unpackZ(key)));
            ByteBufCodecs.registry(Registries.BLOCK_ENTITY_TYPE).encode(buffer, type);
            ByteBufCodecs.TRUSTED_COMPOUND_TAG.encode(buffer, tag);
            return ClientboundBlockEntityDataPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }
}
