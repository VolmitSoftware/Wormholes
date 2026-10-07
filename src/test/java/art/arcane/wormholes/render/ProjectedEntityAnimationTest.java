package art.arcane.wormholes.render;

import com.github.retrooper.packetevents.util.Vector3d;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSwingAnimation;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.bukkit.entity.Player;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import art.arcane.optics.entity.SpoofRegistry;
import art.arcane.optics.entity.SpoofedEntity;
import art.arcane.wormholes.platform.QueuedOpticsScheduler;
import java.util.concurrent.atomic.AtomicInteger;

final class ProjectedEntityAnimationTest {
    private static final AtomicInteger ENTITY_IDS = new AtomicInteger(1);

    @ParameterizedTest
    @EnumSource(value = ServerVersion.class, names = {"V_26_1_2", "V_26_2", "V_26_3"})
    void bothHandsAndHurtSerializeForProjectedPlayersAndMobs(ServerVersion version) {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        recorder.serverVersion(version);
        try {
            Player observer = ProjectedEntityPacketRecorder.player(true);
            EntityRenderPacketChannel channel = new EntityRenderPacketChannel();
            BukkitEntityRegistryHost output = new BukkitEntityRegistryHost(channel, BukkitEntityRegistryHost.PLUGIN_VISIBILITY);
            SpoofRegistry<Player, Vector3d> registry = new SpoofRegistry<>(output);
            ProjectedEntityRenderer renderer = new ProjectedEntityRenderer(output, registry, new QueuedOpticsScheduler());
            for (boolean player : new boolean[] {true, false}) {
                UUID sourceId = player ? observer.getUniqueId() : UUID.randomUUID();
                SpoofedEntity entity = SpoofedEntity.create(ENTITY_IDS::getAndIncrement, player, false, true);
                registry.track(sourceId, entity);
                recorder.sent().clear();

                renderer.sendAnimation(observer, sourceId, EntityAnimationType.SWING_MAIN_ARM);
                renderer.sendAnimation(observer, sourceId, EntityAnimationType.SWING_OFF_HAND);
                renderer.sendHurt(observer, sourceId, 45.0F);
                renderer.sendAnimation(observer, sourceId, EntityAnimationType.CRITICAL_HIT);

                List<PacketWrapper<?>> packets = recorder.sent();
                assertEquals(4, packets.size());
                for (int index = 0; index < packets.size(); index++) {
                    PacketWrapper<?> packet = packets.get(index);
                    ByteBuf buffer = Unpooled.buffer();
                    try {
                        packet.setBuffer(buffer);
                        packet.write();
                        assertEquals(entity.fakeId, packet.readVarInt());
                        if (index < 2 && version == ServerVersion.V_26_3) {
                            assertInstanceOf(WrapperPlayServerSwingAnimation.class, packet);
                            assertEquals(index, packet.readVarInt());
                            assertEquals(1, packet.readVarInt());
                            assertEquals(6, packet.readVarInt());
                        } else if (index == 2) {
                            assertInstanceOf(WrapperPlayServerHurtAnimation.class, packet);
                            assertEquals(45.0F, packet.readFloat());
                        } else {
                            assertInstanceOf(WrapperPlayServerEntityAnimation.class, packet);
                            int expectedId = index == 0 ? 0 : index == 1 ? 3
                                : version == ServerVersion.V_26_3 ? 1 : 4;
                            assertEquals(expectedId, packet.readUnsignedByte());
                        }
                        assertEquals(0, buffer.readableBytes());
                    } finally {
                        buffer.release();
                    }
                }
            }
        } finally {
            recorder.uninstall();
        }
    }

    @ParameterizedTest
    @EnumSource(value = ServerVersion.class, names = {"V_26_1_2", "V_26_2", "V_26_3"})
    void nonLivingProjectionsDoNotReceiveLivingAnimations(ServerVersion version) {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        recorder.serverVersion(version);
        try {
            Player observer = ProjectedEntityPacketRecorder.player(true);
            EntityRenderPacketChannel channel = new EntityRenderPacketChannel();
            BukkitEntityRegistryHost output = new BukkitEntityRegistryHost(channel, BukkitEntityRegistryHost.PLUGIN_VISIBILITY);
            SpoofRegistry<Player, Vector3d> registry = new SpoofRegistry<>(output);
            ProjectedEntityRenderer renderer = new ProjectedEntityRenderer(output, registry, new QueuedOpticsScheduler());
            UUID sourceId = UUID.randomUUID();
            registry.track(sourceId, SpoofedEntity.create(ENTITY_IDS::getAndIncrement, false, false, false));

            renderer.sendAnimation(observer, sourceId, EntityAnimationType.SWING_MAIN_ARM);
            renderer.sendAnimation(observer, sourceId, EntityAnimationType.SWING_OFF_HAND);
            renderer.sendHurt(observer, sourceId, 45.0F);

            assertTrue(recorder.sent().isEmpty());
        } finally {
            recorder.uninstall();
        }
    }
}
