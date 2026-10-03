package art.arcane.wormholes.modded.client;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class ClientProjectedPlayerPhysicsTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void specializedClientPushQueryCannotLetTwoPlayerCopiesPushTheRealPlayer() throws ReflectiveOperationException {
        ClientLevel level = mock(ClientLevel.class);
        Minecraft minecraft = mock(Minecraft.class);
        LocalPlayer player = mock(LocalPlayer.class);
        minecraft.player = player;
        Field minecraftField = ClientLevel.class.getDeclaredField("minecraft");
        minecraftField.setAccessible(true);
        minecraftField.set(level, minecraft);
        when(level.isClientSide()).thenReturn(true);
        when(player.isLocalPlayer()).thenReturn(true);
        when(player.isPushable()).thenReturn(true);
        when(player.getBoundingBox()).thenReturn(new AABB(-0.3, 0, -0.3, 0.3, 2, 0.3));
        AtomicReference<Vec3> velocity = new AtomicReference<>(Vec3.ZERO);
        when(player.getDeltaMovement()).thenAnswer(call -> velocity.get());
        doAnswer(call -> {
            velocity.set(call.getArgument(0));
            return null;
        }).when(player).setDeltaMovement(any(Vec3.class));
        doCallRealMethod().when(player).push(any(Entity.class));
        doCallRealMethod().when(player).push(anyDouble(), anyDouble(), anyDouble());
        RemotePlayer first = copy(level, -100, 0.1);
        RemotePlayer second = copy(level, -101, 0.2);
        doCallRealMethod().when(level).getPushableEntities(any(), any());
        assertEquals(List.of(player), level.getPushableEntities(first, first.getBoundingBox()));
        push(level, first);
        push(level, second);
        assertTrue(velocity.get().lengthSqr() > 0);
        velocity.set(Vec3.ZERO);

        WormholesClient client = mock(WormholesClient.class);
        ClientViewTick tick = mock(ClientViewTick.class);
        ClientProjectedEntities projected = mock(ClientProjectedEntities.class);
        when(client.tickState()).thenReturn(tick);
        when(tick.entities()).thenReturn(projected);
        when(client.reflections()).thenReturn(new ClientReflectionEntity());
        when(projected.meshEntity(-100)).thenReturn(true);
        when(projected.meshEntity(-101)).thenReturn(true);
        doAnswer(call -> ClientMeshEntities.worldPushableEntities(call.getArgument(0),
            (List<Entity>) call.callRealMethod())).when(level).getPushableEntities(any(), any());
        try (MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            clients.when(WormholesClient::instance).thenReturn(client);
            for (int tickIndex = 0; tickIndex < 40; tickIndex++) {
                push(level, first);
                push(level, second);
            }
            assertEquals(Vec3.ZERO, velocity.get());
            RemotePlayer real = copy(level, 100, 0.1);
            List<Entity> ordinary = List.of(player);
            assertSame(ordinary, ClientMeshEntities.worldPushableEntities(real, ordinary));
            push(level, real);
            assertTrue(velocity.get().lengthSqr() > 0);
        }
    }

    @Test
    public void nativePlayerVisualTicksRestorePhysicsAfterPlayerCodeAndFailure() {
        ClientLevel level = mock(ClientLevel.class);
        when(level.isClientSide()).thenReturn(true);
        RemotePlayer copy = copy(level, -100, 0.1);
        when(copy.getInterpolation()).thenReturn(InterpolationHandler.NO_OP);
        doCallRealMethod().when(copy).commonTick();
        when(level.getEntity(-100)).thenReturn(copy);
        ClientLevelScene scene = new ClientLevelScene(level, () -> null);
        doAnswer(call -> {
            copy.noPhysics = false;
            return null;
        }).when(copy).tick();
        scene.tick(-100, 7, true);
        assertTrue(copy.noPhysics);
        assertEquals(1, copy.tickCount);
        doAnswer(call -> {
            copy.noPhysics = false;
            throw new IllegalStateException("visual tick failure");
        }).when(copy).tick();
        assertThrows(IllegalStateException.class, () -> scene.tick(-100, 7, true));
        assertTrue(copy.noPhysics);
        assertEquals(2, copy.tickCount);
    }

    private static RemotePlayer copy(ClientLevel level, int id, double x) {
        RemotePlayer entity = mock(RemotePlayer.class);
        when(entity.getId()).thenReturn(id);
        when(entity.level()).thenReturn(level);
        when(entity.getX()).thenReturn(x);
        when(entity.getBoundingBox()).thenReturn(new AABB(x - 0.3, 0, -0.3, x + 0.3, 2, 0.3));
        when(entity.isPushable()).thenReturn(true);
        return entity;
    }

    private static void push(ClientLevel level, Entity source) {
        for (Entity target : level.getPushableEntities(source, source.getBoundingBox())) {
            target.push(source);
        }
    }
}
