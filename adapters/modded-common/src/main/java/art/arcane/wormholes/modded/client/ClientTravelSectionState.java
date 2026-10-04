package art.arcane.wormholes.modded.client;

import io.netty.buffer.Unpooled;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

record ClientTravelSectionState(byte[] blocks, byte[] sky, byte[] light, Map<BlockPos, CompoundTag> entities) {
    static ClientTravelSectionState capture(ClientLevel level, int x, int y, int z) {
        LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        byte[] blocks = null;
        Map<BlockPos, CompoundTag> entities = new HashMap<>();
        if (chunk != null && y >= chunk.getMinSectionY() && y < chunk.getMinSectionY() + chunk.getSectionsCount()) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y << 4));
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(section.getSerializedSize()));
            try {
                section.write(buffer);
                blocks = new byte[buffer.readableBytes()];
                buffer.readBytes(blocks);
            } finally {
                buffer.release();
            }
            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                if (SectionPos.blockToSectionCoord(entry.getKey().getY()) == y) {
                    entities.put(entry.getKey().immutable(), entry.getValue().getUpdateTag(level.registryAccess()).copy());
                }
            }
        }
        SectionPos position = SectionPos.of(x, y, z);
        return new ClientTravelSectionState(blocks, layer(level, LightLayer.SKY, position),
            layer(level, LightLayer.BLOCK, position), entities);
    }

    boolean same(ClientTravelSectionState other) {
        return other != null && Arrays.equals(blocks, other.blocks) && Arrays.equals(sky, other.sky)
            && Arrays.equals(light, other.light) && entities.equals(other.entities);
    }

    private static byte[] layer(ClientLevel level, LightLayer layer, SectionPos position) {
        DataLayer data = level.getLightEngine().getLayerListener(layer).getDataLayerData(position);
        return data == null ? null : data.copy().getData();
    }
}
