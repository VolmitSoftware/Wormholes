package art.arcane.wormholes.neoforge;

import art.arcane.wormholes.modded.seamless.SeamlessMove;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.network.payload.AuxiliaryLightDataPayload;

import java.util.HashMap;
import java.util.Map;

final class NeoForgeSeamlessEvents implements SeamlessMove.Events {
    @Override
    public boolean allowLevelChange(ServerPlayer player, ServerLevel destination) {
        return CommonHooks.onTravelToDimension(player, destination.dimension());
    }

    @Override
    public void levelChanged(ServerPlayer player, ServerLevel origin, ServerLevel destination) {
        EventHooks.firePlayerChangedDimensionEvent(player, origin.dimension(), destination.dimension());
    }

    @Override
    public void chunkWatched(ServerPlayer player, ServerLevel level, LevelChunk chunk) {
        EventHooks.fireChunkWatch(player, chunk, level);
        LevelChunkAuxiliaryLightManager lights = chunk.getAuxLightManager(chunk.getPos());
        if (lights != null) {
            Map<BlockPos, Byte> entries = auxiliaryLights(lights.serializeNBT());
            if (!entries.isEmpty()) {
                player.connection.send(new ClientboundCustomPayloadPacket(new AuxiliaryLightDataPayload(chunk.getPos(), entries)));
            }
        }
        EventHooks.fireChunkSent(player, chunk, level);
    }

    @Override
    public void chunkUnwatched(SeamlessMove.ChunkLeave leave) {
        EventHooks.fireChunkUnWatch(leave.player(), leave.pos(), leave.level());
    }

    @Override
    public void entityTracked(ServerPlayer player, Entity entity) {
        EventHooks.onStartEntityTracking(entity, player);
    }

    @Override
    public void entityUntracked(ServerPlayer player, Entity entity) {
        EventHooks.onStopEntityTracking(entity, player);
    }

    private static Map<BlockPos, Byte> auxiliaryLights(ListTag list) {
        Map<BlockPos, Byte> entries = new HashMap<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            if (list.get(index) instanceof CompoundTag entry) {
                entries.put(BlockPos.of(entry.getLongOr("pos", 0L)), entry.getByteOr("level", (byte) 0));
            }
        }
        return entries;
    }
}
