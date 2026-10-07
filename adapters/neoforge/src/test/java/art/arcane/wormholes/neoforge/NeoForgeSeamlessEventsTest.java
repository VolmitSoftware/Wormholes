package art.arcane.wormholes.neoforge;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager;
import net.neoforged.neoforge.network.payload.AuxiliaryLightDataPayload;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class NeoForgeSeamlessEventsTest {
    private static final ChunkPos CHUNK = new ChunkPos(3, -2);

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void watchingAChunkWithoutAuxiliaryLightSendsNoLightPayload() {
        ServerPlayer player = player();
        LevelChunk chunk = chunk();
        when(chunk.getAuxLightManager(CHUNK)).thenReturn(new LevelChunkAuxiliaryLightManager(chunk));

        new NeoForgeSeamlessEvents().chunkWatched(player, mock(ServerLevel.class), chunk);

        verify(player.connection, never()).send(any(Packet.class));
    }

    @Test
    public void watchingAChunkWithAuxiliaryLightSendsItsEntries() {
        ServerPlayer player = player();
        LevelChunk chunk = chunk();
        BlockPos lit = new BlockPos(50, 64, -30);
        LevelChunkAuxiliaryLightManager lights = new LevelChunkAuxiliaryLightManager(chunk);
        CompoundTag entry = new CompoundTag();
        entry.putLong("pos", lit.asLong());
        entry.putByte("level", (byte) 11);
        ListTag stored = new ListTag();
        stored.add(entry);
        lights.deserializeNBT(stored);
        when(chunk.getAuxLightManager(CHUNK)).thenReturn(lights);

        new NeoForgeSeamlessEvents().chunkWatched(player, mock(ServerLevel.class), chunk);

        ArgumentCaptor<Packet<?>> sent = ArgumentCaptor.captor();
        verify(player.connection).send(sent.capture());
        AuxiliaryLightDataPayload payload = (AuxiliaryLightDataPayload) ((ClientboundCustomPayloadPacket) sent.getValue()).payload();
        assertEquals(CHUNK, payload.pos());
        assertEquals(Map.of(lit, (byte) 11), payload.entries());
    }

    private static ServerPlayer player() {
        ServerPlayer player = mock(ServerPlayer.class);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        return player;
    }

    private static LevelChunk chunk() {
        LevelChunk chunk = mock(LevelChunk.class);
        when(chunk.getPos()).thenReturn(CHUNK);
        when(chunk.getAttachmentHolder()).thenReturn(mock(AttachmentHolder.AsField.class));
        return chunk;
    }
}
