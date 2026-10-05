package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.network.client.ClientViewMessage;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static art.arcane.wormholes.modded.client.ClientTravelTestFixtures.set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientTravelCrossingDeclineTest extends MinecraftTestBase {
    @Test
    public void unreadyPreparationDeclinesOnlyAtItsPhysicalApertureAndKeepsArrivalCover() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            set(fixture.travel, "acknowledgedRevision", 50L);
            set(fixture.travel, "staged", mock(ClientLevel.class));
            Object arrival = mock(Class.forName(ClientPreparedTravel.class.getName() + "$Arrival"));
            set(fixture.travel, "arrival", arrival);
            fixture.eye(new Vec3(0.5, 1.62, 0.3));
            fixture.eye(new Vec3(0.5, 1.62, 0.4));
            assertTrue(fixture.sent.isEmpty());
            assertTrue(fixture.travel.active());
            fixture.eye(new Vec3(0.5, 1.62, 0.6));
            assertEquals(List.of(new ClientViewMessage.TravelCancel(fixture.begin.token(), fixture.begin.generation())), fixture.sent);
            assertFalse(fixture.travel.active());
            assertSame(arrival, get(fixture.travel, "arrival"));
            assertNull(get(fixture.travel, "prediction"));
            verify(fixture.renderer).cancelTravel();
            verify(fixture.renderer, never()).retireArrival();
            fixture.eye(new Vec3(0.5, 1.62, 0.7));
            assertEquals(1, fixture.sent.size());
        }
    }

    @Test
    public void crossingAnotherWorldOrOutsideTheApertureDoesNotDecline() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            fixture.eye(new Vec3(2.5, 1.62, 0.3));
            fixture.eye(new Vec3(2.5, 1.62, 0.6));
            when(fixture.level.dimension()).thenReturn(Level.OVERWORLD);
            fixture.eye(new Vec3(0.5, 1.62, 0.3));
            fixture.eye(new Vec3(0.5, 1.62, 0.6));
            assertTrue(fixture.sent.isEmpty());
            assertTrue(fixture.travel.active());
            verify(fixture.renderer, never()).cancelTravel();
        }
    }

    @Test
    public void ordinaryAuthoritativeTeleportCannotBecomeAContinuousPortalCrossing() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            fixture.eye(new Vec3(0.5, 1.62, 0.3));
            fixture.travel.beforeServerPosition();
            assertNull(get(fixture.travel, "previousCamera"));
            fixture.eye(new Vec3(0.5, 1.62, 0.8));
            assertTrue(fixture.sent.isEmpty());
            assertTrue(fixture.travel.active());
            assertSame(fixture.begin, get(fixture.travel, "begin"));
            verify(fixture.renderer, never()).cancelTravel();
        }
    }

    private static Object get(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class Fixture implements AutoCloseable {
        private final Minecraft minecraft = mock(Minecraft.class);
        private final LocalPlayer player = mock(LocalPlayer.class);
        private final ClientLevel level = mock(ClientLevel.class);
        private final Camera camera = mock(Camera.class);
        private final DeltaTracker tracker = mock(DeltaTracker.class);
        private final ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
        private final List<ClientViewMessage> sent = new ArrayList<>();
        private final ClientPreparedTravel travel = new ClientPreparedTravel(sent::add);
        private final ClientViewMessage.TravelBegin begin;
        private final MockedStatic<Minecraft> minecraftAccess;
        private final MockedStatic<ClientPortalRenderer> rendererAccess;

        private Fixture() throws ReflectiveOperationException {
            Method fixture = ClientPreparedTravelPendingTest.class.getDeclaredMethod("begin", long.class);
            fixture.setAccessible(true);
            begin = (ClientViewMessage.TravelBegin) fixture.invoke(null, 12L);
            set(travel, "begin", begin);
            set(travel, "deadline", System.currentTimeMillis() + 30_000);
            minecraft.player = player;
            minecraft.level = level;
            Field gui = Minecraft.class.getDeclaredField("gui");
            set(minecraft, "gui", mock(gui.getType()));
            ClientPacketListener connection = mock(ClientPacketListener.class);
            when(minecraft.getConnection()).thenReturn(connection);
            when(connection.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            when(level.dimension()).thenReturn(Level.NETHER);
            when(camera.isInitialized()).thenReturn(true);
            when(camera.entity()).thenReturn(player);
            when(camera.getCameraEntityPartialTicks(tracker)).thenReturn(0.5f);
            minecraftAccess = mockStatic(Minecraft.class);
            minecraftAccess.when(Minecraft::getInstance).thenReturn(minecraft);
            rendererAccess = mockStatic(ClientPortalRenderer.class);
            rendererAccess.when(ClientPortalRenderer::instance).thenReturn(renderer);
        }

        private void eye(Vec3 position) {
            when(player.getEyePosition(0.5f)).thenReturn(position);
            assertFalse(travel.beforeFrame(camera, tracker));
        }

        @Override
        public void close() {
            rendererAccess.close();
            minecraftAccess.close();
        }
    }
}
