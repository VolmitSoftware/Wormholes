package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.stream.BrickLightSource;
import art.arcane.optics.scan.ProjectorSample;
import art.arcane.optics.stream.ClientViewPlateHandoff;
import art.arcane.optics.plate.PlateCell;
import art.arcane.optics.plate.ViewPlate;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class MinecraftClientViewHandoffs implements ClientViewPlateHandoff<BlockState> {
    @Override
    public long publish(int portalKey, int plateRevision, ViewPlate<BlockState> plate, BrickLightSource light) {
        return LocalPlateHandles.publish(portalKey, plateRevision, plate, backingState(plate), light).handle();
    }

    static BlockState backingState(ViewPlate<BlockState> plate) {
        Reference2IntOpenHashMap<BlockState> backing = new Reference2IntOpenHashMap<>(16);
        Reference2IntOpenHashMap<BlockState> occluded = new Reference2IntOpenHashMap<>(16);
        LongIterator keys = plate.cellKeys().iterator();
        while (keys.hasNext()) {
            PlateCell<BlockState> cell = plate.cell(keys.nextLong());
            if (cell == null || cell.data() == null) {
                continue;
            }
            if (cell.kind() == ProjectorSample.Kind.BACKING_BLOCK) {
                backing.addTo(cell.data(), 1);
            } else if (cell.kind() == ProjectorSample.Kind.OCCLUDED) {
                occluded.addTo(cell.data(), 1);
            }
        }
        BlockState vote = vote(backing);
        if (vote == null) {
            vote = vote(occluded);
        }
        return vote == null ? Blocks.STONE.defaultBlockState() : vote;
    }

    private static BlockState vote(Reference2IntOpenHashMap<BlockState> votes) {
        BlockState best = null;
        String bestKey = null;
        int bestCount = 0;
        for (Reference2IntOpenHashMap.Entry<BlockState> entry : votes.reference2IntEntrySet()) {
            int count = entry.getIntValue();
            if (count < bestCount) {
                continue;
            }
            String key = BlockStateParser.serialize(entry.getKey());
            if (count > bestCount || key.compareTo(bestKey) < 0) {
                best = entry.getKey();
                bestKey = key;
                bestCount = count;
            }
        }
        return best;
    }
}
