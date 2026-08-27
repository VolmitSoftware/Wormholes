package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;

public final class EntityRenderPacketChannelTest {
    @Test
    public void emptyBatchSkipsFlush() {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.installWithBatchUser();
        try {
            EntityRenderPacketChannel channel = new EntityRenderPacketChannel();
            channel.begin(ProjectedEntityPacketRecorder.player(true));

            channel.end();

            assertEquals(1, recorder.batchLookups());
            assertEquals(0, recorder.batchFlushes());
        } finally {
            recorder.uninstall();
        }
    }

    @Test
    public void writtenBatchStillFlushesOnce() {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.installWithBatchUser();
        try {
            Player observer = ProjectedEntityPacketRecorder.player(true);
            EntityRenderPacketChannel channel = new EntityRenderPacketChannel();
            channel.begin(observer);
            channel.send(observer, new WrapperPlayServerDestroyEntities(101));

            channel.end();

            assertEquals(1, recorder.batchLookups());
            assertEquals(1, recorder.batchFlushes());
            assertEquals(1, recorder.packetsAtLastFlush());
        } finally {
            recorder.uninstall();
        }
    }

    @Test
    public void failedBatchWriteStillFlushesPossiblePartialDelivery() {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.installWithBatchUser();
        try {
            Player observer = ProjectedEntityPacketRecorder.player(true);
            EntityRenderPacketChannel channel = new EntityRenderPacketChannel();
            channel.begin(observer);
            recorder.failNextSend();

            assertThrows(IllegalStateException.class,
                () -> channel.send(observer, new WrapperPlayServerDestroyEntities(101)));
            channel.end();

            assertEquals(1, recorder.batchLookups());
            assertEquals(1, recorder.batchFlushes());
        } finally {
            recorder.uninstall();
        }
    }
}
