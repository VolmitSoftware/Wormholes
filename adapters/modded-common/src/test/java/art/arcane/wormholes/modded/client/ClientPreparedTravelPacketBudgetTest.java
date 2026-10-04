package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.mixin.client.PreparedLevelAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.wormholes.network.client.ClientViewMessage;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class ClientPreparedTravelPacketBudgetTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void ordinaryMetadataBurstExceedsOldCountLimitAndReplaysInOrderOnRejection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (int index = 0; index < 512; index++) {
                int sequence = index;
                assertTrue(fixture.travel.deferWorldPacket(metadata(index), () -> fixture.replayed.add(sequence)));
            }
            assertTrue(fixture.travel.pendingCrossing());
            assertEquals(512, fixture.packets.size());
            assertTrue(fixture.replayed.isEmpty());
            assertTrue((int) get(fixture.prediction, "retainedPacketBytes") > 512 * 1024);
            assertEquals(fixture.deadline, get(fixture.prediction, "deadline"));
            Method rollback = ClientPreparedTravel.class.getDeclaredMethod("rollback");
            rollback.setAccessible(true);
            rollback.invoke(fixture.travel);
            fixture.assertRejectedAndReplayed(512);
        }
    }

    @Test
    public void retainedMetadataBurstRollsBackAtMemoryBudgetWithoutDroppingOrReordering() throws Exception {
        try (Fixture fixture = new Fixture()) {
            int accepted = 0;
            boolean overflowed = false;
            for (int index = 0; index < 9000; index++) {
                int sequence = index;
                if (!fixture.travel.deferWorldPacket(metadata(index), () -> fixture.replayed.add(sequence))) {
                    overflowed = true;
                    break;
                }
                accepted++;
            }
            assertTrue(overflowed);
            assertTrue(accepted > 512);
            assertEquals(fixture.deadline, get(fixture.prediction, "deadline"));
            fixture.assertRejectedAndReplayed(accepted);
            verify(fixture.renderer).cancelTravel();
            assertFalse(fixture.travel.deferWorldPacket(metadata(9001), () -> fixture.replayed.add(9001)));
            assertEquals(accepted, fixture.replayed.size());
        }
    }

    private static ClientboundSetEntityDataPacket metadata(int entityId) {
        return new ClientboundSetEntityDataPacket(entityId,
            List.of(new SynchedEntityData.DataValue<>(1, EntityDataSerializers.INT, entityId)));
    }

    private static Object get(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Class<?> owner = target.getClass();
        while (owner != null) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException failure) {
                owner = owner.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static final class Fixture implements AutoCloseable {
        private final Minecraft minecraft = mock(Minecraft.class);
        private final ClientPacketListener listener = mock(ClientPacketListener.class, withSettings().extraInterfaces(PreparedPacketAccess.class));
        private final ClientLevel source = mock(ClientLevel.class, withSettings().extraInterfaces(PreparedLevelAccess.class));
        private final ClientLevel.ClientLevelData data = mock(ClientLevel.ClientLevelData.class);
        private final ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
        private final ArrayDeque<Runnable> packets = new ArrayDeque<>();
        private final ArrayList<Integer> replayed = new ArrayList<>();
        private final ArrayList<ClientViewMessage> sent = new ArrayList<>();
        private final long deadline = System.currentTimeMillis() + 2000;
        private final Object prediction;
        private final ClientPreparedTravel travel = new ClientPreparedTravel(sent::add);
        private final MockedStatic<Minecraft> minecraftAccess;
        private final MockedStatic<ClientPortalRenderer> rendererAccess;

        private Fixture() throws ReflectiveOperationException {
            Class<?> type = Class.forName(ClientPreparedTravel.class.getName() + "$Prediction");
            prediction = mock(type);
            set(prediction, "source", source);
            set(prediction, "extractor", mock(LevelExtractor.class));
            set(prediction, "packets", packets);
            set(prediction, "sourceColumns", new ArrayList<>());
            set(prediction, "deadline", deadline);
            set(prediction, "protocol", GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY)));
            set(travel, "prediction", prediction);
            set(travel, "staged", mock(ClientLevel.class));
            ClientViewMessage.TravelBegin begin = mock(ClientViewMessage.TravelBegin.class);
            when(begin.token()).thenReturn(new UUID(17, 23));
            when(begin.generation()).thenReturn(7L);
            set(travel, "begin", begin);
            when(minecraft.isSameThread()).thenReturn(true);
            when(minecraft.getConnection()).thenReturn(listener);
            when(listener.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            when(source.getLevelData()).thenReturn(data);
            minecraftAccess = mockStatic(Minecraft.class);
            minecraftAccess.when(Minecraft::getInstance).thenReturn(minecraft);
            rendererAccess = mockStatic(ClientPortalRenderer.class);
            rendererAccess.when(ClientPortalRenderer::instance).thenReturn(renderer);
        }

        private void assertRejectedAndReplayed(int accepted) {
            assertFalse(travel.pendingCrossing());
            assertTrue(packets.isEmpty());
            assertEquals(accepted, replayed.size());
            for (int index = 0; index < accepted; index++) {
                assertEquals(index, replayed.get(index).intValue());
            }
            assertEquals(List.of(new ClientViewMessage.TravelCancel(new UUID(17, 23), 7)), sent);
            verify((PreparedPacketAccess) listener).wormholes$level(source);
            verify((PreparedPacketAccess) listener).wormholes$data(data);
        }

        @Override
        public void close() {
            rendererAccess.close();
            minecraftAccess.close();
        }
    }
}
