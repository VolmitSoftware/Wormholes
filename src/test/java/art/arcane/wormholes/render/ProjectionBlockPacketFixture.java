package art.arcane.wormholes.render;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.BitSet;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.HeightmapType;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import java.util.function.BiConsumer;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

final class ProjectionBlockPacketFixture implements AutoCloseable {
    private final ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
    private final List<ByteBuf> buffers = new ArrayList<ByteBuf>();
    private final UUID observerId;

    ProjectionBlockPacketFixture(UUID observerId) {
        this.observerId = observerId;
    }

    PacketSendEvent event(PacketWrapper<?> wrapper) {
        ByteBuf buffer = buffer();
        wrapper.setBuffer(buffer);
        wrapper.write();
        return event(wrapper.getPacketTypeData().getPacketType(), buffer);
    }

    PacketSendEvent chunk(int x, int z) {
        ByteBuf buffer = buffer();
        buffer.writeInt(x);
        buffer.writeInt(z);
        return event(PacketType.Play.Server.CHUNK_DATA, buffer);
    }

    PacketSendEvent fullChunk(int x, int z, int biome) {
        Chunk_v1_18 section = new Chunk_v1_18(ServerVersion.getLatest().toClientVersion());
        for (int index = 0; index < 64; index++) {
            section.getBiomeData().set(index & 3, (index >> 4) & 3, (index >> 2) & 3, biome);
        }
        Column column = new Column(x, z, true, new BaseChunk[] {section}, new TileEntity[0], Map.<HeightmapType, long[]>of());
        LightData light = new LightData(true, new BitSet(), new BitSet(), new BitSet(), new BitSet(),
            0, 0, new byte[0][], new byte[0][]);
        return event(new WrapperPlayServerChunkData(column, light));
    }

    PacketSendEvent lifecycle(PacketType.Play.Server type) {
        return event(type, buffer());
    }

    void send(ProjectionClientChunkTracker tracker, PacketSendEvent event) {
        tracker.onPacketSend(event);
        finish(event);
    }

    PacketSendEvent unload(int x, int z) {
        ByteBuf buffer = buffer();
        buffer.writeLong(ProjectionClientChunkTracker.chunkKey(x, z));
        return event(PacketType.Play.Server.UNLOAD_CHUNK, buffer);
    }

    void finish(PacketSendEvent event) {
        finish(event, true, null);
    }

    void fail(PacketSendEvent event) {
        finish(event, false, new IllegalStateException("transport write failed"));
    }

    void finish(PacketSendEvent event, boolean success, Throwable failure) {
        for (BiConsumer<Boolean, Throwable> task : event.getTasksOnWriteCompletion()) {
            task.accept(success && !event.isCancelled(), failure);
        }
        for (Runnable task : event.getTasksAfterSend()) {
            task.run();
        }
    }

    @Override
    public void close() {
        for (ByteBuf buffer : buffers) {
            buffer.release();
        }
        recorder.uninstall();
    }

    private ByteBuf buffer() {
        ByteBuf buffer = Unpooled.buffer();
        buffers.add(buffer);
        return buffer;
    }

    private PacketSendEvent event(PacketTypeCommon type, ByteBuf buffer) {
        User user = mock(User.class);
        when(user.getUUID()).thenReturn(observerId);
        when(user.getTotalWorldHeight()).thenReturn(16);
        when(user.getDimensionType()).thenReturn(DimensionTypes.OVERWORLD);
        when(user.getClientVersion()).thenReturn(ServerVersion.getLatest().toClientVersion());
        PacketSendEvent event = mock(PacketSendEvent.class);
        when(event.getUser()).thenReturn(user);
        when(event.getPacketType()).thenReturn(type);
        when(event.getServerVersion()).thenReturn(ServerVersion.getLatest());
        when(event.getByteBuf()).thenReturn(buffer);
        when(event.getTasksAfterSend()).thenReturn(new ArrayList<Runnable>());
        when(event.getTasksOnWriteCompletion()).thenReturn(new ArrayList<BiConsumer<Boolean, Throwable>>());
        return event;
    }
}
