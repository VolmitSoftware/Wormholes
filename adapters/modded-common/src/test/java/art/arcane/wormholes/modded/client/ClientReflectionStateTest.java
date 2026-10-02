package art.arcane.wormholes.modded.client;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class ClientReflectionStateTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void reflectionUsesEachSourceHandAndExactUseClockWithoutRestarting() throws ReflectiveOperationException {
        for (InteractionHand hand : InteractionHand.values()) {
            Fixture fixture = new Fixture();
            ItemStack apple = item(32);
            fixture.hands.put(hand, apple);
            fixture.sourceFlags = hand == InteractionHand.MAIN_HAND ? (byte) 1 : (byte) 3;
            fixture.sourceRemaining = 25;
            fixture.follow();
            assertSame(apple, fixture.mirror.getUseItem());
            assertEquals(hand, fixture.mirror.getUsedItemHand());
            assertEquals(7, fixture.mirror.getTicksUsingItem());
            fixture.sourceRemaining = 24;
            fixture.follow();
            assertEquals(8, fixture.mirror.getTicksUsingItem());
            assertEquals(1, fixture.flagUpdates.get());
        }
    }

    @Test
    public void stoppingAndSwitchingItemsDoNotRetainThePreviousUseAnimation() throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        ItemStack apple = item(32);
        fixture.hands.put(InteractionHand.MAIN_HAND, apple);
        fixture.sourceFlags = 1;
        fixture.sourceRemaining = 25;
        fixture.follow();
        fixture.sourceFlags = 0;
        fixture.sourceRemaining = 0;
        fixture.follow();
        assertFalse(fixture.mirror.isUsingItem());
        assertSame(ItemStack.EMPTY, fixture.mirror.getUseItem());
        assertEquals(0, fixture.mirror.getUseItemRemainingTicks());
        ItemStack shield = item(72000);
        fixture.hands.put(InteractionHand.OFF_HAND, shield);
        fixture.sourceFlags = 3;
        fixture.sourceRemaining = 71996;
        fixture.follow();
        assertSame(shield, fixture.mirror.getUseItem());
        assertEquals(InteractionHand.OFF_HAND, fixture.mirror.getUsedItemHand());
        assertEquals(4, fixture.mirror.getTicksUsingItem());
        ItemStack bow = item(72000);
        fixture.hands.put(InteractionHand.OFF_HAND, bow);
        fixture.sourceRemaining = 71992;
        fixture.follow();
        assertSame(bow, fixture.mirror.getUseItem());
        assertEquals(8, fixture.mirror.getTicksUsingItem());
        assertEquals(3, fixture.flagUpdates.get());
    }

    @Test
    public void hurtAndDeathClocksFollowTheSourceWithoutReplayingAnEvent() throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        fixture.source.hurtDuration = 10;
        fixture.source.hurtTime = 9;
        fixture.source.deathTime = 4;
        fixture.follow();
        assertEquals(10, fixture.mirror.hurtDuration);
        assertEquals(9, fixture.mirror.hurtTime);
        assertEquals(4, fixture.mirror.deathTime);
        fixture.source.hurtTime = 8;
        fixture.source.deathTime = 5;
        fixture.follow();
        assertEquals(8, fixture.mirror.hurtTime);
        assertEquals(5, fixture.mirror.deathTime);
    }

    private static ItemStack item(int duration) {
        ItemStack item = mock(ItemStack.class);
        when(item.getUseDuration(any(LivingEntity.class))).thenReturn(duration);
        return item;
    }

    private static final class Fixture {
        private final LocalPlayer source = mock(LocalPlayer.class);
        private final Mannequin mirror = mock(Mannequin.class, withSettings().extraInterfaces(LivingEntityUseState.class));
        private final EnumMap<InteractionHand, ItemStack> hands = new EnumMap<>(InteractionHand.class);
        private final AtomicInteger flagUpdates = new AtomicInteger();
        private final EntityDataAccessor<Byte> flags;
        private byte sourceFlags;
        private byte mirrorFlags;
        private int sourceRemaining;

        @SuppressWarnings("unchecked")
        private Fixture() throws ReflectiveOperationException {
            Field flagsField = LivingEntity.class.getDeclaredField("DATA_LIVING_ENTITY_FLAGS");
            flagsField.setAccessible(true);
            flags = (EntityDataAccessor<Byte>) flagsField.get(null);
            Field useItem = LivingEntity.class.getDeclaredField("useItem");
            Field remaining = LivingEntity.class.getDeclaredField("useItemRemaining");
            useItem.setAccessible(true);
            remaining.setAccessible(true);
            useItem.set(mirror, ItemStack.EMPTY);
            SynchedEntityData sourceData = mock(SynchedEntityData.class);
            SynchedEntityData mirrorData = mock(SynchedEntityData.class);
            when(source.getEntityData()).thenReturn(sourceData);
            when(mirror.getEntityData()).thenReturn(mirrorData);
            when(sourceData.get(flags)).thenAnswer(ignored -> sourceFlags);
            when(source.isUsingItem()).thenAnswer(ignored -> (sourceFlags & 1) != 0);
            when(mirror.isUsingItem()).thenAnswer(ignored -> (mirrorFlags & 1) != 0);
            when(source.getUsedItemHand()).thenAnswer(ignored -> (sourceFlags & 2) != 0 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            when(mirror.getUsedItemHand()).thenAnswer(ignored -> (mirrorFlags & 2) != 0 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            when(source.getUseItemRemainingTicks()).thenAnswer(ignored -> sourceRemaining);
            when(mirror.getItemInHand(any(InteractionHand.class))).thenAnswer(call -> hands.getOrDefault(call.getArgument(0), ItemStack.EMPTY));
            ClientLevel level = mock(ClientLevel.class);
            when(level.isClientSide()).thenReturn(true);
            when(mirror.level()).thenReturn(level);
            doCallRealMethod().when(mirror).onSyncedDataUpdated(any(EntityDataAccessor.class));
            doCallRealMethod().when(mirror).getUseItem();
            doCallRealMethod().when(mirror).getUseItemRemainingTicks();
            doCallRealMethod().when(mirror).getTicksUsingItem();
            doAnswer(call -> {
                byte incoming = call.getArgument(1);
                if (incoming != mirrorFlags) {
                    mirrorFlags = incoming;
                    flagUpdates.incrementAndGet();
                    mirror.onSyncedDataUpdated(flags);
                }
                return null;
            }).when(mirrorData).set(eq(flags), anyByte());
            LivingEntityUseState accessor = (LivingEntityUseState) mirror;
            doAnswer(call -> {
                useItem.set(mirror, call.getArgument(0));
                return null;
            }).when(accessor).wormholesUseItem(any(ItemStack.class));
            doAnswer(call -> {
                remaining.setInt(mirror, call.getArgument(0));
                return null;
            }).when(accessor).wormholesUseItemRemaining(anyInt());
        }

        private void follow() {
            ClientReflectionEntity.followAnimation(mirror, source, flags);
        }
    }
}
