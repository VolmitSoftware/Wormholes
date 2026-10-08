package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class ClientTravelCrossingDeclineTest extends MinecraftTestBase {
    @Test
    public void crossingIntoAClosedResidentLevelIsDeclinedWithoutMovingThePlayer() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            fixture.eye(new Vec3(0.5, 1.62, 0.3));
            fixture.eye(new Vec3(0.5, 1.62, 0.4));
            assertNull(fixture.declined());
            fixture.eye(new Vec3(0.5, 1.62, 0.6));
            assertEquals(fixture.begin.token(), fixture.declined());
            assertTrue(fixture.sent.isEmpty());
            assertFalse(fixture.travel.pending());
            assertSame(fixture.level, fixture.minecraft.level);
            fixture.eye(new Vec3(0.5, 1.62, 0.7));
            assertTrue(fixture.sent.isEmpty());
        }
    }

    @Test
    public void crossingAnotherWorldOrOutsideTheApertureIsNeverAttempted() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            fixture.eye(new Vec3(2.5, 1.62, 0.3));
            fixture.eye(new Vec3(2.5, 1.62, 0.6));
            when(fixture.level.dimension()).thenReturn(Level.NETHER);
            fixture.eye(new Vec3(0.5, 1.62, 0.3));
            fixture.eye(new Vec3(0.5, 1.62, 0.6));
            assertNull(fixture.declined());
            assertTrue(fixture.sent.isEmpty());
        }
    }

    @Test
    public void ordinaryAuthoritativeTeleportCannotBecomeAContinuousPortalCrossing() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            fixture.eye(new Vec3(0.5, 1.62, 0.3));
            fixture.travel.serverPosition();
            fixture.eye(new Vec3(0.5, 1.62, 0.8));
            assertNull(fixture.declined());
            assertTrue(fixture.sent.isEmpty());
            assertTrue(fixture.travel.armed(fixture.begin.sourcePortal()));
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Minecraft minecraft = mock(Minecraft.class);
        private final LocalPlayer player = SeamlessTravelFixtures.player();
        private final ClientLevel level = mock(ClientLevel.class);
        private final Camera camera = mock(Camera.class);
        private final DeltaTracker tracker = mock(DeltaTracker.class);
        private final List<TravelMessage> sent = new ArrayList<>();
        private final ClientSeamlessTravel travel = ClientTravelTestFixtures.travel(sent::add);
        private final TravelMessage.TravelBegin begin = SeamlessTravelFixtures.begin(true);
        private final MockedStatic<Minecraft> minecraftAccess;

        private Fixture() {
            minecraft.player = player;
            minecraft.level = level;
            when(minecraft.getConnection()).thenReturn(mock(ClientPacketListener.class));
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(camera.isInitialized()).thenReturn(true);
            when(camera.entity()).thenReturn(player);
            when(camera.getCameraEntityPartialTicks(tracker)).thenReturn(0.5f);
            minecraftAccess = mockStatic(Minecraft.class);
            minecraftAccess.when(Minecraft::getInstance).thenReturn(minecraft);
            assertTrue(travel.receive(begin));
        }

        private Object declined() throws ReflectiveOperationException {
            Field field = ClientSeamlessTravel.class.getDeclaredField("declined");
            field.setAccessible(true);
            return field.get(travel);
        }

        private void eye(Vec3 position) {
            when(player.getEyePosition(0.5f)).thenReturn(position);
            when(player.getPosition(0.5f)).thenReturn(position.subtract(0, 1.62, 0));
            assertFalse(travel.beforeFrame(camera, tracker));
        }

        @Override
        public void close() {
            minecraftAccess.close();
        }
    }
}
