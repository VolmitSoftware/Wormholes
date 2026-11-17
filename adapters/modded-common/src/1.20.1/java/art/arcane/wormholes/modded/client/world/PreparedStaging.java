package art.arcane.wormholes.modded.client.world;

import art.arcane.wormholes.modded.client.ClientArrivalPreparation;
import art.arcane.wormholes.modded.client.PreparedChunkColumns;
import art.arcane.wormholes.network.client.ArrivalMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.concurrent.atomic.AtomicReferenceArray;

/** The prepared-arrival backend: detached levels, their terrain compiler and the terrain hand-over. */
final class PreparedStaging implements ClientArrivalPreparation.Staging {
    private static final int LIGHT_SECTION_BYTES = 2 * 2048;

    @Override
    public ClientLevel open(ArrivalMessage.Prepare prepare) {
        return PreparedLevels.arrival(prepare);
    }

    @Override
    public ClientArrivalPreparation.Terrain terrain(ClientLevel level, ArrivalMessage.Prepare prepare) {
        return new PreparedTerrainRenderer(level, prepare);
    }

    @Override
    public void transfer(ClientLevel prepared, ClientLevel active) {
        PreparedTerrain.transfer(prepared, active);
    }

    @Override
    public long estimate(ClientLevel level) {
        AtomicReferenceArray<LevelChunk> columns = ((PreparedChunkColumns) level.getChunkSource()).wormholes$columns();
        if (columns == null) {
            return 0L;
        }
        long bytes = 0L;
        for (int index = 0; index < columns.length(); index++) {
            LevelChunk column = columns.get(index);
            if (column == null) {
                continue;
            }
            for (LevelChunkSection section : column.getSections()) {
                bytes += section.getSerializedSize() + LIGHT_SECTION_BYTES;
            }
        }
        return bytes;
    }
}
