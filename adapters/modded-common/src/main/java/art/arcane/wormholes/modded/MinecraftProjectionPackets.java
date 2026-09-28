package art.arcane.wormholes.modded;

import art.arcane.wormholes.render.ProjectionCellKey;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

public final class MinecraftProjectionPackets {
    private final WormholesModRuntime runtime;

    public MinecraftProjectionPackets(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public void sendBlocks(ServerPlayer player, ServerLevel world, Long2ObjectMap<BlockState> blocks) {
        runtime.requireServerThread();
        if (player.hasDisconnected() || player.level() != world || blocks.isEmpty()) {
            return;
        }
        Long2ObjectMap<Short2ObjectMap<BlockState>> sections = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<BlockState> entry : blocks.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            if (world.isOutsideBuildHeight(y)
                || !world.getChunkSource().chunkMap.isChunkTracked(player, x >> 4, z >> 4)) {
                continue;
            }
            long section = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
            Short2ObjectMap<BlockState> changes = sections.computeIfAbsent(section, ignored -> new Short2ObjectOpenHashMap<>());
            changes.put((short) (((x & 15) << 8) | ((z & 15) << 4) | (y & 15)), entry.getValue());
        }
        for (Long2ObjectMap.Entry<Short2ObjectMap<BlockState>> section : sections.long2ObjectEntrySet()) {
            sendSection(player, section.getLongKey(), section.getValue());
        }
    }

    void sendSection(ServerPlayer player, long section, Short2ObjectMap<BlockState> changes) {
        int limit = MinecraftClientProfiles.profile(player).blockBatchLimit();
        if (changes.size() <= limit) {
            player.connection.send(sectionPacket(section, changes));
            return;
        }
        Short2ObjectMap<BlockState> batch = new Short2ObjectOpenHashMap<>(limit);
        for (Short2ObjectMap.Entry<BlockState> entry : changes.short2ObjectEntrySet()) {
            batch.put(entry.getShortKey(), entry.getValue());
            if (batch.size() == limit) {
                player.connection.send(sectionPacket(section, batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) { player.connection.send(sectionPacket(section, batch)); }
    }

    public void restoreBlocks(ServerPlayer player, ServerLevel world, LongSet cells) {
        runtime.requireServerThread();
        if (player.hasDisconnected() || player.level() != world || cells.isEmpty()) {
            return;
        }
        Long2ObjectMap<BlockState> restored = new Long2ObjectOpenHashMap<>(cells.size());
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        LongIterator iterator = cells.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            LevelChunk chunk = world.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk != null && !world.isOutsideBuildHeight(y)) {
                restored.put(key, chunk.getBlockState(position.set(x, y, z)));
            }
        }
        sendBlocks(player, world, restored);
    }

    static ClientboundSectionBlocksUpdatePacket sectionPacket(long section, Short2ObjectMap<BlockState> changes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(10 + changes.size() * 5));
        try {
            SectionPos.STREAM_CODEC.encode(buffer, SectionPos.of(section));
            buffer.writeVarInt(changes.size());
            for (Short2ObjectMap.Entry<BlockState> entry : changes.short2ObjectEntrySet()) {
                buffer.writeVarLong(((long) Block.getId(entry.getValue()) << 12) | (entry.getShortKey() & 4095));
            }
            return ClientboundSectionBlocksUpdatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }
}
