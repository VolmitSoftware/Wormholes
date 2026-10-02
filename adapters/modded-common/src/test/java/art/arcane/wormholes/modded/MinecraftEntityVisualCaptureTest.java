package art.arcane.wormholes.modded;

import art.arcane.wormholes.network.view.EntityDeltaCodec;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
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
        state.blobCaptureStates().put(id, new ViewEntityState.BlobCaptureState<>(1, Pose.STANDING, false, 1));
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
}
