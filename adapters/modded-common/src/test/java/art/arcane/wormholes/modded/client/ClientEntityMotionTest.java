package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.wormholes.network.client.ClientViewMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.UUID;
import java.util.List;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ClientEntityMotionTest extends MinecraftTestBase {
    @Test
    public void nativeDisplaysAdvanceWithoutLoadedDestinationChunks() {
        ClientLevel level = mock(ClientLevel.class);
        Display[] displays = {mock(Display.ItemDisplay.class), mock(Display.BlockDisplay.class), mock(Display.TextDisplay.class)};
        ClientLevelScene scene = new ClientLevelScene(level, () -> null);
        for (int i = 0; i < displays.length; i++) {
            Display display = displays[i];
            when(level.getEntity(i)).thenReturn(display);
            scene.tick(i, 7, false);
            verify(display, never()).commonTick();
            verify(display, never()).tick();
            scene.tick(i, 7, true);
            verify(display).commonTick();
            verify(display).tick();
        }
        verify(level, never()).getBlockState(any());
        verify(level, never()).getFluidState(any());
    }

    @Test
    public void nativeLivingEntitiesUseOneExplicitClockWithoutConsultingDestinationChunks() {
        ClientLevel level = mock(ClientLevel.class);
        when(level.isClientSide()).thenReturn(true);
        LivingEntity living = mock(LivingEntity.class);
        when(living.level()).thenReturn(level);
        when(living.getInterpolation()).thenReturn(InterpolationHandler.NO_OP);
        doCallRealMethod().when(living).commonTick();
        when(level.getEntity(42)).thenReturn(living);
        ClientLevelScene scene = new ClientLevelScene(level, () -> null);
        for (int i = 0; i < 20; i++) {
            scene.tick(42, 7, true);
        }
        assertEquals(20, living.tickCount);
        assertTrue(living.noPhysics);
        verify(living, times(20)).commonTick();
        verify(living, times(20)).tick();
        scene.tick(42, 7, false);
        assertEquals(20, living.tickCount);
        verify(living, times(20)).tick();
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
        EntitySnapshot start = visual(1000, 64, 0, 0);
        EntitySnapshot next = visual(1001, 63, 0, 0);
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
        EntitySnapshot start = visual(1000, 64, 0, 0);
        EntitySnapshot moving = visual(1001, 63, 0, 0);
        motion.move(moving, start);
        motion.tick();
        EntitySnapshot teleported = visual(-500, 80, 0, 0);
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
        EntitySnapshot forward = visual(0, 64, 25, 0);
        EntitySnapshot headTurn = visual(0, 64, 25, 90);
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

    @Test
    public void initialHandUseFindsTheAuthoritativeEquipmentForBothHands() throws ReflectiveOperationException {
        for (InteractionHand hand : InteractionHand.values()) {
            assertHandUseInitialized(hand, false);
        }
    }

    @Test
    public void simultaneousEquipmentAndUseMetadataInitializeTheNewItem() throws ReflectiveOperationException {
        for (InteractionHand hand : InteractionHand.values()) {
            assertHandUseInitialized(hand, true);
        }
    }

    private static void assertHandUseInitialized(InteractionHand hand, boolean update) throws ReflectiveOperationException {
        ClientLevel level = mock(ClientLevel.class);
        when(level.isClientSide()).thenReturn(true);
        LivingEntity living = mock(LivingEntity.class);
        when(living.level()).thenReturn(level);
        when(living.getUsedItemHand()).thenReturn(hand);
        AtomicBoolean using = new AtomicBoolean();
        when(living.isUsingItem()).thenAnswer(ignored -> using.get());
        AtomicReference<ItemStack> held = new AtomicReference<>(ItemStack.EMPTY);
        when(living.getItemInHand(hand)).thenAnswer(ignored -> held.get());
        doCallRealMethod().when(living).stopUsingItem();
        doCallRealMethod().when(living).onSyncedDataUpdated(any(EntityDataAccessor.class));
        doCallRealMethod().when(living).getUseItem();
        doCallRealMethod().when(living).getUseItemRemainingTicks();
        living.stopUsingItem();
        ItemStack apple = mock(ItemStack.class);
        when(apple.getUseDuration(living)).thenReturn(32);
        Field flagsField = LivingEntity.class.getDeclaredField("DATA_LIVING_ENTITY_FLAGS");
        flagsField.setAccessible(true);
        EntityDataAccessor<?> flags = (EntityDataAccessor<?>) flagsField.get(null);
        ClientSceneWorld world = mock(ClientSceneWorld.class);
        when(world.spawn(anyInt(), any(UUID.class), any(EntitySnapshot.class))).thenReturn(true);
        doAnswer(call -> {
            byte[] equipment = call.getArgument(1);
            held.set(equipment[0] == 0 ? ItemStack.EMPTY : apple);
            return null;
        }).when(world).equipment(anyInt(), any(byte[].class));
        doAnswer(call -> {
            byte[] metadata = call.getArgument(1);
            using.set(metadata[0] != 0);
            living.onSyncedDataUpdated(flags);
            return null;
        }).when(world).metadata(anyInt(), any(byte[].class));
        ClientProjectedEntities entities = new ClientProjectedEntities(world);
        ClientPortal portal = mock(ClientPortal.class);
        UUID id = UUID.randomUUID();
        if (update) {
            entities.apply(new ClientViewMessage.EntityFrame(1, 1, List.of(usingVisual(id, 0)), List.of(id), true));
            entities.tick(ignored -> portal, ignored -> true);
        }
        entities.apply(new ClientViewMessage.EntityFrame(1, 2, List.of(usingVisual(id, hand == InteractionHand.MAIN_HAND ? 1 : 3)),
            List.of(id), true));
        entities.tick(ignored -> portal, ignored -> true);
        assertSame(apple, living.getUseItem());
        assertEquals(32, living.getUseItemRemainingTicks());
        assertEquals(hand, living.getUsedItemHand());
        entities.apply(new ClientViewMessage.EntityFrame(1, 3, List.of(usingVisual(id, hand == InteractionHand.MAIN_HAND ? 1 : 3)),
            List.of(id), true));
        entities.tick(ignored -> portal, ignored -> true);
        verify(living, times(update ? 2 : 1)).onSyncedDataUpdated(flags);
    }

    private static EntitySnapshot usingVisual(UUID id, int flags) {
        return EntitySnapshot.full(id, "minecraft:zombie", 0, 64, 0, 1.95D, 0, 0, 1, 0, 0, 0, 0, 0, true,
            "", "", "", null, null, new byte[] {(byte) flags}, new byte[] {(byte) (flags == 0 ? 0 : 1)}, 0);
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

    private static EntitySnapshot visual(double x, double y, float bodyYaw, float headYaw) {
        double radians = Math.toRadians(headYaw);
        return EntitySnapshot.full(UUID.randomUUID(), "minecraft:item", x, y, 2000, 0.25D,
            -Math.sin(radians), 0, Math.cos(radians), bodyYaw, 0, 0.25D, -0.1D, 0, false,
            "", "", "", null, null, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, 0);
    }
}
