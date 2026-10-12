package art.arcane.wormholes.network;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MinecraftEntitySnapshotGravityTest extends MinecraftTestBase {
    @Test
    public void transferCopiesUseWorldRotationForTheRootAndEveryPassenger() {
        for (int index = 0; index < 48; index++) {
            if (AxisPermutation.ofIndex(index).reflects()) {
                continue;
            }
            GravityFrame frame = MinecraftGravity.fromIndex(index);
            CompoundTag source = actor(frame, 32.0F, -19.0F);
            ListTag passengers = new ListTag();
            passengers.add(actor(frame, -75.0F, 27.0F));
            source.put("Passengers", passengers);
            CompoundTag transfer = source.copy();

            MinecraftEntitySnapshots.normalizeGravity(transfer);

            assertWorldRotation(transfer, frame.toWorld(Angles.direction(32.0F, -19.0F)));
            assertWorldRotation((CompoundTag) transfer.getListOrEmpty("Passengers").get(0),
                frame.toWorld(Angles.direction(-75.0F, 27.0F)));
            assertTrue(source.contains("wormholesGravity"));
            assertTrue(((CompoundTag) source.getListOrEmpty("Passengers").get(0)).contains("wormholesGravity"));
            assertEquals(32.0F, source.getListOrEmpty("Rotation").getFloatOr(0, 0.0F), 0.0F);
        }
    }

    private static CompoundTag actor(GravityFrame frame, float yaw, float pitch) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("wormholesGravity", frame.orientation().index());
        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(yaw));
        rotation.add(FloatTag.valueOf(pitch));
        tag.put("Rotation", rotation);
        return tag;
    }

    private static void assertWorldRotation(CompoundTag tag, Vec3d expected) {
        assertFalse(tag.contains("wormholesGravity"));
        ListTag rotation = tag.getListOrEmpty("Rotation");
        Vec3d actual = Angles.direction(rotation.getFloatOr(0, 0.0F), rotation.getFloatOr(1, 0.0F));
        assertEquals(expected.x(), actual.x(), 1.0E-5D);
        assertEquals(expected.y(), actual.y(), 1.0E-5D);
        assertEquals(expected.z(), actual.z(), 1.0E-5D);
    }
}
