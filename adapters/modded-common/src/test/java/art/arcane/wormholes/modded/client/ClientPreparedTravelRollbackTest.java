package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.mixin.client.PreparedLevelAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.wormholes.network.client.ClientViewMessage;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class ClientPreparedTravelRollbackTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void rejectedPredictionRestoresBothListenerWorldAndData() throws ReflectiveOperationException {
        Minecraft minecraft = mock(Minecraft.class);
        ClientPacketListener listener = mock(ClientPacketListener.class, withSettings().extraInterfaces(PreparedPacketAccess.class));
        ClientLevel source = mock(ClientLevel.class, withSettings().extraInterfaces(PreparedLevelAccess.class));
        ClientLevel.ClientLevelData data = mock(ClientLevel.ClientLevelData.class);
        when(source.getLevelData()).thenReturn(data);
        when(minecraft.getConnection()).thenReturn(listener);
        ClientPreparedTravel travel = predicted(source);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            rollback(travel);
        }
        verify((PreparedPacketAccess) listener).wormholes$level(source);
        verify((PreparedPacketAccess) listener).wormholes$data(data);
        assertFalse(travel.pendingCrossing());
    }

    @Test
    public void acceptedDestinationIsNotReturnedToSourceByFallbackCleanup() throws ReflectiveOperationException {
        Minecraft minecraft = mock(Minecraft.class);
        ClientPacketListener listener = mock(ClientPacketListener.class, withSettings().extraInterfaces(PreparedPacketAccess.class));
        ClientLevel source = mock(ClientLevel.class, withSettings().extraInterfaces(PreparedLevelAccess.class));
        ClientLevel destination = mock(ClientLevel.class);
        when(minecraft.getConnection()).thenReturn(listener);
        set(minecraft, "level", destination);
        ClientPreparedTravel travel = predicted(source);
        set(travel, "staged", destination);
        set(travel, "adopted", true);
        set(travel, "commit", mock(ClientViewMessage.TravelCommit.class));
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            rollback(travel);
        }
        assertSame(destination, minecraft.level);
        verify((PreparedPacketAccess) listener, never()).wormholes$level(any());
        verify((PreparedPacketAccess) listener, never()).wormholes$data(any());
        assertFalse(travel.pendingCrossing());
    }

    private static ClientPreparedTravel predicted(ClientLevel source) throws ReflectiveOperationException {
        Class<?> type = Class.forName(ClientPreparedTravel.class.getName() + "$Prediction");
        Object prediction = mock(type);
        set(prediction, "source", source);
        set(prediction, "extractor", mock(LevelExtractor.class));
        set(prediction, "packets", new ArrayDeque<Runnable>());
        set(prediction, "sourceColumns", new ArrayList<>());
        ClientPreparedTravel travel = new ClientPreparedTravel(ignored -> { });
        set(travel, "prediction", prediction);
        return travel;
    }

    private static void rollback(ClientPreparedTravel travel) throws ReflectiveOperationException {
        Method method = ClientPreparedTravel.class.getDeclaredMethod("rollback");
        method.setAccessible(true);
        method.invoke(travel);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Class<?> owner = target instanceof Minecraft ? Minecraft.class : target.getClass();
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
}
