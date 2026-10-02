package art.arcane.wormholes.modded;

import art.arcane.wormholes.modded.mixin.EntityDataAccess;
import art.arcane.wormholes.network.view.EntityDeltaCodec;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.ViewEntityState;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

public class MinecraftEntityVisualCaptureTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void stationaryAnimalHeadTurnHasIndependentLookDelta() {
        LivingEntity animal = mock(LivingEntity.class);
        UUID id = UUID.randomUUID();
        when(animal.getUUID()).thenReturn(id);
        SynchedEntityData data = mock(SynchedEntityData.class, withSettings().extraInterfaces(EntityDataRevision.class));
        when(animal.getEntityData()).thenReturn(data);
        doReturn(EntityTypes.COW).when(animal).getType();
        when(animal.position()).thenReturn(new Vec3(10, 64, 20));
        when(animal.getDeltaMovement()).thenReturn(Vec3.ZERO);
        when(animal.getPose()).thenReturn(Pose.STANDING);
        when(animal.getYRot()).thenReturn(35F);
        animal.yBodyRot = 25F;
        when(animal.getHeadLookAngle()).thenReturn(new Vec3(0, 0, 1));
        when(animal.getLookAngle()).thenReturn(new Vec3(0.5D, 0, 0.5D));
        ViewEntityState<Pose> state = new ViewEntityState<>(UUID.randomUUID(), new ViewEntityState.Center(0, 0, 0));
        EntityVisual previous = EntityVisual.full(id, "minecraft:cow", 10, 64, 20, 0, 0, 0, 1,
            25, 0, 0, 0, 0, false, "", "", "", null, null, EntityVisual.EMPTY, EntityVisual.EMPTY, 0);
        state.lastCapturedSnapshots().put(id, previous);
        state.blobCaptureStates().put(id, new ViewEntityState.BlobCaptureState<>(1, Pose.STANDING, false, 1, 0L));
        MinecraftEntityVisualCapture capture = new MinecraftEntityVisualCapture(mock(MinecraftPacketBlobs.class));
        EntityVisual first = capture.capture(animal, state, 2);
        when(animal.getHeadLookAngle()).thenReturn(new Vec3(-1, 0, 0));
        EntityVisual turned = capture.capture(animal, state, 3);
        assertEquals(25, turned.yaw(), 0);
        assertEquals(-1, turned.lookX(), 0);
        assertEquals(0, turned.lookZ(), 0);
        int mask = EntityDeltaCodec.computeMask(turned, first);
        assertTrue((mask & EntityVisual.FIELD_LOOK_VEC) != 0);
        assertEquals(0, mask & (EntityVisual.FIELD_POSITION | EntityVisual.FIELD_YAW_PITCH));
    }
    @Test
    public void shortHandUseBabyAndAggressiveMetadataChangesAreCapturedImmediately() {
        LivingEntity animal = mock(LivingEntity.class);
        UUID id = UUID.randomUUID();
        when(animal.getUUID()).thenReturn(id);
        doReturn(EntityTypes.COW).when(animal).getType();
        when(animal.position()).thenReturn(new Vec3(10, 64, 20));
        when(animal.getDeltaMovement()).thenReturn(Vec3.ZERO);
        when(animal.getPose()).thenReturn(Pose.STANDING);
        when(animal.getHeadLookAngle()).thenReturn(new Vec3(0, 0, 1));
        SynchedEntityData data = mock(SynchedEntityData.class, withSettings().extraInterfaces(EntityDataRevision.class, EntityDataAccess.class));
        when(animal.getEntityData()).thenReturn(data);
        AtomicLong revision = new AtomicLong();
        when(((EntityDataRevision) data).wormholesRevision()).thenAnswer(call -> revision.get());
        SynchedEntityData.DataItem<Byte> hand = new SynchedEntityData.DataItem<>(new EntityDataAccessor<>(0, EntityDataSerializers.BYTE), (byte) 0);
        SynchedEntityData.DataItem<Boolean> baby = new SynchedEntityData.DataItem<>(new EntityDataAccessor<>(1, EntityDataSerializers.BOOLEAN), false);
        SynchedEntityData.DataItem<Byte> mob = new SynchedEntityData.DataItem<>(new EntityDataAccessor<>(2, EntityDataSerializers.BYTE), (byte) 0);
        when(((EntityDataAccess) data).wormholesItems()).thenReturn(new SynchedEntityData.DataItem<?>[] {hand, baby, mob});
        MinecraftPacketBlobs blobs = mock(MinecraftPacketBlobs.class);
        when(blobs.writeMetadata(anyList())).thenAnswer(call -> {
            List<SynchedEntityData.DataValue<?>> values = call.getArgument(0);
            return new byte[] {(Byte) values.get(0).value(), (byte) ((Boolean) values.get(1).value() ? 1 : 0), (Byte) values.get(2).value()};
        });
        ViewEntityState<Pose> state = new ViewEntityState<>(UUID.randomUUID(), new ViewEntityState.Center(0, 0, 0));
        MinecraftEntityVisualCapture capture = new MinecraftEntityVisualCapture(blobs);
        EntityVisual idle = capture.capture(animal, state, 1);
        assertEquals(0, idle.metadata()[0]);
        hand.setValue((byte) 1);
        revision.incrementAndGet();
        EntityVisual using = capture.capture(animal, state, 2);
        assertEquals(1, using.metadata()[0]);
        assertTrue((EntityDeltaCodec.computeMask(using, idle) & EntityVisual.FIELD_METADATA) != 0);
        hand.setValue((byte) 0);
        revision.incrementAndGet();
        EntityVisual stopped = capture.capture(animal, state, 3);
        assertEquals(0, stopped.metadata()[0]);
        baby.setValue(true);
        revision.incrementAndGet();
        assertEquals(1, capture.capture(animal, state, 4).metadata()[1]);
        mob.setValue((byte) 4);
        revision.incrementAndGet();
        assertEquals(4, capture.capture(animal, state, 5).metadata()[2]);
        capture.capture(animal, state, 6);
        verify(blobs, times(5)).writeMetadata(anyList());
    }
}
