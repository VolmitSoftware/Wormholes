package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.view.EntityVisual;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ClientEntityMotionTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void nativeDisplaysAdvanceWithoutLoadedDestinationChunks() {
        ClientLevel level = mock(ClientLevel.class);
        Display[] displays = {mock(Display.ItemDisplay.class), mock(Display.BlockDisplay.class), mock(Display.TextDisplay.class)};
        ClientLevelScene scene = new ClientLevelScene(level, () -> null);
        for (int i = 0; i < displays.length; i++) {
            Display display = displays[i];
            when(level.getEntity(i)).thenReturn(display);
            scene.tick(i, false);
            verify(display, never()).commonTick();
            verify(display, never()).tick();
            scene.tick(i, true);
            verify(display).commonTick();
            verify(display).tick();
        }
        verify(level, never()).getBlockState(any());
        verify(level, never()).getFluidState(any());
    }

    @Test
    public void itemSnapshotsInterpolateWithoutLocalPhysicsAndStopAtTheLastSample() {
        ClientLevel level = mock(ClientLevel.class);
        when(level.isClientSide()).thenReturn(true);
        ItemEntity item = item(level);
        item.setPos(1000, 64, 2000);
        item.setOldPosAndRot();
        ClientItemMotion motion = new ClientItemMotion(item);
        EntityVisual start = visual(1000, 64, 0, 0);
        EntityVisual next = visual(1001, 63, 0, 0);
        motion.move(next, start);
        assertEquals(1000, item.getX(), 0);
        motion.tick();
        assertEquals(1000, item.xOld, 0);
        assertEquals(1000.5D, item.getX(), 1.0E-9D);
        assertEquals(63.5D, item.getY(), 1.0E-9D);
        motion.tick();
        assertEquals(1001, item.getX(), 0);
        assertEquals(63, item.getY(), 0);
        for (int i = 0; i < 10; i++) {
            motion.tick();
        }
        assertEquals(1001, item.getX(), 0);
        assertEquals(63, item.getY(), 0);
        assertEquals(12, item.tickCount);
        verify(level, never()).getBlockState(any());
        verify(level, never()).getFluidState(any());
    }

    @Test
    public void itemTeleportCancelsTheOldPathAndResetsTheRenderOrigin() {
        ClientLevel level = mock(ClientLevel.class);
        when(level.isClientSide()).thenReturn(true);
        ItemEntity item = item(level);
        item.setPos(1000, 64, 2000);
        ClientItemMotion motion = new ClientItemMotion(item);
        EntityVisual start = visual(1000, 64, 0, 0);
        EntityVisual moving = visual(1001, 63, 0, 0);
        motion.move(moving, start);
        motion.tick();
        EntityVisual teleported = visual(-500, 80, 0, 0);
        motion.move(teleported, moving);
        assertEquals(-500, item.getX(), 0);
        assertEquals(-500, item.xOld, 0);
        assertEquals(80, item.yOld, 0);
        motion.tick();
        assertEquals(-500, item.getX(), 0);
        assertEquals(80, item.getY(), 0);
    }

    @Test
    public void headTurnsDoNotRestartBodyInterpolationAndBodyMovementDoesNotResetHead() {
        Entity entity = mock(Entity.class);
        EntityVisual forward = visual(0, 64, 25, 0);
        EntityVisual headTurn = visual(0, 64, 25, 90);
        ClientLevelScene.move(entity, headTurn, forward);
        verify(entity).lerpHeadTo(90, 3);
        verify(entity, never()).moveOrInterpolateTo(any(Vec3.class), anyFloat(), anyFloat());
        Entity moving = mock(Entity.class);
        ClientLevelScene.move(moving, visual(1, 64, 25, 90), headTurn);
        verify(moving).moveOrInterpolateTo(new Vec3(1, 64, 2000), 25, 0);
        verify(moving, never()).lerpHeadTo(anyFloat(), anyInt());
        Entity unchanged = mock(Entity.class);
        ClientLevelScene.move(unchanged, headTurn, headTurn);
        verifyNoInteractions(unchanged);
        assertEquals(-1, ClientLevelScene.headYaw(visual(0, 64, 25, 359)), 0.0001F);
    }

    private static ItemEntity item(ClientLevel level) {
        ItemEntity item = mock(ItemEntity.class);
        AtomicReference<Vec3> position = new AtomicReference<>(Vec3.ZERO);
        when(item.level()).thenReturn(level);
        when(item.getInterpolation()).thenReturn(InterpolationHandler.NO_OP);
        when(item.position()).thenAnswer(ignored -> position.get());
        when(item.getX()).thenAnswer(ignored -> position.get().x);
        when(item.getY()).thenAnswer(ignored -> position.get().y);
        when(item.getZ()).thenAnswer(ignored -> position.get().z);
        when(item.storePositionAndRotation()).thenAnswer(ignored -> PositionAndRotation.of(position.get(), 0, 0));
        doAnswer(call -> {
            position.set(call.getArgument(0));
            return null;
        }).when(item).setPos(any(Vec3.class));
        doAnswer(call -> {
            position.set(new Vec3(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            return null;
        }).when(item).setPos(anyDouble(), anyDouble(), anyDouble());
        doAnswer(ignored -> {
            Vec3 previous = position.get();
            item.xOld = previous.x;
            item.yOld = previous.y;
            item.zOld = previous.z;
            return null;
        }).when(item).setOldPosAndRot();
        doCallRealMethod().when(item).commonTick();
        return item;
    }

    private static EntityVisual visual(double x, double y, float bodyYaw, float headYaw) {
        double radians = Math.toRadians(headYaw);
        return EntityVisual.full(UUID.randomUUID(), "minecraft:item", x, y, 2000, 0.25D,
            -Math.sin(radians), 0, Math.cos(radians), bodyYaw, 0, 0.25D, -0.1D, 0, false,
            "", "", "", null, null, EntityVisual.EMPTY, EntityVisual.EMPTY, 0);
    }
}
