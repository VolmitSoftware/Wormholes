package art.arcane.wormholes.modded.gravity;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.door.DoorArrivals;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.door.DoorTransit;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.world.entity.Entity;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class MinecraftGravityDoorOrientationTest extends MinecraftTestBase {
    @Test
    public void everyGravityBasisRetainsWorldFacingAcrossOrdinaryDoorPlanning() {
        DoorwayPlane source = new DoorwayPlane(0, 64, 0, Face.S);
        DoorwayPlane destination = new DoorwayPlane(10, 64, 10, Face.E);
        for (int index = 0; index < 48; index++) {
            AxisPermutation rotation = AxisPermutation.ofIndex(index);
            if (rotation.reflects()) {
                continue;
            }
            GravityFrame frame = MinecraftGravity.fromIndex(index);
            Entity entity = mock(Entity.class, withSettings().extraInterfaces(GravityHolder.class));
            when(((GravityHolder) entity).wormholesGravity()).thenReturn(frame);
            when(entity.getYRot()).thenReturn(34.0F);
            when(entity.getXRot()).thenReturn(-12.0F);
            Angles.Look world = MinecraftGravity.worldLook(entity);
            Vec3d expectedDeparture = frame.toWorld(Angles.direction(34.0F, -12.0F));
            assertVector(expectedDeparture, Angles.direction(world.yaw(), world.pitch()));
            DoorArrivals.Facing facing = DoorArrivals.arrivalFacing(destination, new DoorTransit(source, true, world.yaw(), world.pitch()), -1);
            Angles.Look local = MinecraftGravity.localLook(entity, new Angles.Look(facing.yaw(), facing.pitch()));
            assertVector(Angles.direction(facing.yaw(), facing.pitch()), frame.toWorld(Angles.direction(local.yaw(), local.pitch())));
        }
    }

    private static void assertVector(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x(), actual.x(), 1.0E-5D);
        assertEquals(expected.y(), actual.y(), 1.0E-5D);
        assertEquals(expected.z(), actual.z(), 1.0E-5D);
    }
}
