package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;

public final class ProjectorBlackoutDisplayRendererTest {
    @Test
    public void exactPanelsKeepStableIdsAndSendOnlyDeltas() {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        try {
            Player observer = ProjectedEntityPacketRecorder.player(true);
            ProjectorBlackoutDisplayRenderer renderer =
                new ProjectorBlackoutDisplayRenderer(new EntityRenderPacketChannel());
            ProjectorBlackoutMesh.Panel first = new ProjectorBlackoutMesh.Panel(0, 1, 4, 2, 3, 4, 5);
            ProjectorBlackoutMesh.Panel second = new ProjectorBlackoutMesh.Panel(1, -1, 9, 6, 7, 2, 3);
            List<ProjectorBlackoutMesh.Panel> panels = List.of(first, second);

            assertTrue(renderer.prepare(observer, panels, 41, 48.0D));
            renderer.finish(observer);

            List<WrapperPlayServerSpawnEntity> initialSpawns =
                recorder.sentOfType(WrapperPlayServerSpawnEntity.class);
            assertEquals(2, initialSpawns.size());
            assertEquals(2, recorder.sentOfType(WrapperPlayServerEntityMetadata.class).size());
            assertInstanceOf(WrapperPlayServerSpawnEntity.class, recorder.sent().get(0));
            assertInstanceOf(WrapperPlayServerEntityMetadata.class, recorder.sent().get(1));
            int firstId = initialSpawns.get(0).getEntityId();
            int secondId = initialSpawns.get(1).getEntityId();
            int settledPackets = recorder.sent().size();

            assertTrue(renderer.prepare(observer, panels, 41, 48.0D));
            renderer.finish(observer);
            assertEquals(settledPackets, recorder.sent().size());

            assertTrue(renderer.prepare(observer, panels, 42, 48.0D));
            renderer.finish(observer);
            assertEquals(2, recorder.sentOfType(WrapperPlayServerSpawnEntity.class).size());
            assertEquals(4, recorder.sentOfType(WrapperPlayServerEntityMetadata.class).size());

            assertTrue(renderer.prepare(observer, List.of(first), 42, 48.0D));
            renderer.finish(observer);
            List<WrapperPlayServerDestroyEntities> destroys =
                recorder.sentOfType(WrapperPlayServerDestroyEntities.class);
            assertEquals(1, destroys.size());
            assertArrayEquals(new int[] { secondId }, destroys.get(0).getEntityIds());
            assertEquals(1, renderer.getPaneCount());

            renderer.close(observer);
            destroys = recorder.sentOfType(WrapperPlayServerDestroyEntities.class);
            assertEquals(2, destroys.size());
            assertArrayEquals(new int[] { firstId }, destroys.get(1).getEntityIds());
            assertEquals(0, renderer.getPaneCount());
        } finally {
            recorder.uninstall();
        }
    }

    @Test
    public void failedDestroyStaysRetryable() {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        try {
            Player observer = ProjectedEntityPacketRecorder.player(true);
            ProjectorBlackoutDisplayRenderer renderer =
                new ProjectorBlackoutDisplayRenderer(new EntityRenderPacketChannel());
            ProjectorBlackoutMesh.Panel panel = new ProjectorBlackoutMesh.Panel(2, 1, 12, 3, 4, 5, 6);
            renderer.prepare(observer, List.of(panel), 7, 32.0D);
            renderer.finish(observer);

            recorder.failNextSend();
            renderer.close(observer);
            assertEquals(1, renderer.getPaneCount());

            renderer.close(observer);
            assertEquals(0, renderer.getPaneCount());
            assertEquals(1, recorder.sentOfType(WrapperPlayServerDestroyEntities.class).size());
        } finally {
            recorder.uninstall();
        }
    }

    @Test
    public void failedSpawnAndCleanupAreRetriedWithAFreshEntity() {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        try {
            Player observer = ProjectedEntityPacketRecorder.player(true);
            ProjectorBlackoutDisplayRenderer renderer =
                new ProjectorBlackoutDisplayRenderer(new EntityRenderPacketChannel());
            ProjectorBlackoutMesh.Panel panel = new ProjectorBlackoutMesh.Panel(0, 1, 4, 2, 3, 4, 5);
            List<ProjectorBlackoutMesh.Panel> panels = List.of(panel);

            recorder.failNextSends(2);
            assertFalse(renderer.prepare(observer, panels, 41, 48.0D));
            renderer.prepareEmpty();
            renderer.finish(observer);
            assertEquals(1, renderer.getPaneCount());

            assertTrue(renderer.prepare(observer, panels, 41, 48.0D));
            renderer.finish(observer);

            assertEquals(1, recorder.sentOfType(WrapperPlayServerSpawnEntity.class).size());
            assertEquals(1, recorder.sentOfType(WrapperPlayServerDestroyEntities.class).size());
            assertEquals(1, renderer.getPaneCount());
        } finally {
            recorder.uninstall();
        }
    }

    @Test
    public void failedFlushAndCleanupAreRetriedWithAFreshEntity() {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        try {
            FailingFlushChannel channel = new FailingFlushChannel(2);
            Player observer = ProjectedEntityPacketRecorder.player(true);
            ProjectorBlackoutDisplayRenderer renderer = new ProjectorBlackoutDisplayRenderer(channel);
            ProjectorBlackoutMesh.Panel panel = new ProjectorBlackoutMesh.Panel(0, 1, 4, 2, 3, 4, 5);
            List<ProjectorBlackoutMesh.Panel> panels = List.of(panel);

            assertFalse(renderer.prepare(observer, panels, 41, 48.0D));
            renderer.prepareEmpty();
            renderer.finish(observer);
            assertEquals(1, renderer.getPaneCount());

            assertTrue(renderer.prepare(observer, panels, 41, 48.0D),
                "remainingFlushFailures=" + channel.failedFlushesRemaining
                    + " endCalls=" + channel.endCalls
                    + " queued=" + channel.queued.size()
                    + " sent=" + channel.sent.size());
            renderer.finish(observer);

            assertEquals(1, channel.sentOfType(WrapperPlayServerSpawnEntity.class).size());
            assertEquals(1, channel.sentOfType(WrapperPlayServerDestroyEntities.class).size());
            assertEquals(1, renderer.getPaneCount());
        } finally {
            recorder.uninstall();
        }
    }

    @Test
    public void metadataIsFullBrightShadowlessAndUsesTheExactThinTransform() {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        try {
            ProjectorBlackoutMesh.Transform transform =
                new ProjectorBlackoutMesh.Transform(1.0D, 2.0D, 3.0D, 4.0D, 5.0D, ProjectorBlackoutMesh.PANEL_THICKNESS);
            List<EntityData<?>> metadata =
                ProjectorBlackoutDisplayRenderer.displayMetadata(transform, 1234, 2.5F);
            Map<Integer, Object> values = new HashMap<Integer, Object>();
            for (EntityData<?> value : metadata) {
                values.put(Integer.valueOf(value.getIndex()), value.getValue());
            }

            assertEquals(Integer.valueOf(ProjectorBlackoutDisplayRenderer.FULL_BRIGHT), values.get(Integer.valueOf(16)));
            assertEquals(Float.valueOf(2.5F), values.get(Integer.valueOf(17)));
            assertEquals(Float.valueOf(0.0F), values.get(Integer.valueOf(20)));
            assertEquals(Float.valueOf(0.0F), values.get(Integer.valueOf(21)));
            assertEquals(Integer.valueOf(1234), values.get(Integer.valueOf(23)));
            Vector3f scale = (Vector3f) values.get(Integer.valueOf(12));
            assertEquals(4.0F, scale.getX());
            assertEquals(5.0F, scale.getY());
            assertEquals((float) ProjectorBlackoutMesh.PANEL_THICKNESS, scale.getZ());
        } finally {
            recorder.uninstall();
        }
    }

    @Test
    public void rawMetadataRendererIsRestrictedToLeafsPinnedSchema() {
        assertTrue(ProjectorBlackoutDisplayRenderer.supports(ServerVersion.V_26_2));
        assertFalse(ProjectorBlackoutDisplayRenderer.supports(ServerVersion.V_26_1_2));
        assertEquals(1.5F, ProjectorBlackoutDisplayRenderer.viewRange(32.0D));
        assertEquals(32.0F, ProjectorBlackoutDisplayRenderer.viewRange(1024.0D));
    }

    @Test
    public void viewRangeIncludesObserverDistanceAndTheFarthestPanelOrigin() {
        ProjectorBlackoutMesh.Panel farCap =
            new ProjectorBlackoutMesh.Panel(2, -1, -64, 0, 64, 16, 16);

        float viewRange = ProjectorBlackoutDisplayRenderer.viewRange(
            0.5D, 65.5D, 32.5D, List.of(farCap), 64.0D);

        assertTrue(viewRange > 3.5F);
    }

    @Test
    public void offlineDiscardSendsNothing() {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        try {
            Player observer = ProjectedEntityPacketRecorder.player(true);
            ProjectorBlackoutDisplayRenderer renderer =
                new ProjectorBlackoutDisplayRenderer(new EntityRenderPacketChannel());
            renderer.prepare(observer,
                List.of(new ProjectorBlackoutMesh.Panel(0, 1, 4, 2, 3, 4, 5)),
                41, 48.0D);
            renderer.finish(observer);
            int packetsBeforeDiscard = recorder.sent().size();

            renderer.discard();

            assertEquals(packetsBeforeDiscard, recorder.sent().size());
            assertEquals(0, renderer.getPaneCount());
        } finally {
            recorder.uninstall();
        }
    }

    private static final class FailingFlushChannel extends EntityRenderPacketChannel {
        private final List<PacketWrapper<?>> queued;
        private final List<PacketWrapper<?>> sent;
        private int failedFlushesRemaining;
        private int endCalls;

        private FailingFlushChannel(int failedFlushes) {
            this.queued = new ArrayList<PacketWrapper<?>>();
            this.sent = new ArrayList<PacketWrapper<?>>();
            this.failedFlushesRemaining = failedFlushes;
            this.endCalls = 0;
        }

        @Override
        void begin(Player observer) {
        }

        @Override
        void send(Player observer, PacketWrapper<?> packet) {
            queued.add(packet);
        }

        @Override
        void end() {
            endCalls++;
            if (failedFlushesRemaining > 0) {
                failedFlushesRemaining--;
                queued.clear();
                throw new IllegalStateException("injected packet flush failure");
            }
            sent.addAll(queued);
            queued.clear();
        }

        private <T extends PacketWrapper<?>> List<T> sentOfType(Class<T> type) {
            List<T> matches = new ArrayList<T>();
            for (PacketWrapper<?> packet : sent) {
                if (type.isInstance(packet)) {
                    matches.add(type.cast(packet));
                }
            }
            return matches;
        }
    }
}
