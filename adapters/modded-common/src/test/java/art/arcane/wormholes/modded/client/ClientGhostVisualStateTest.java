package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.entity.EntityVisualMetadata;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

public class ClientGhostVisualStateTest extends MinecraftTestBase {
    @Test
    public void spawnAppliesFrameAndEffectiveScaleBeforeLocalModelAngles() {
        LivingEntity ghost = mock(LivingEntity.class);
        AttributeInstance scale = new AttributeInstance(Attributes.SCALE, changed -> {
        });
        when(ghost.getAttribute(Attributes.SCALE)).thenReturn(scale);
        GravityFrame sideways = new GravityFrame(AxisPermutation.of(Face.D, Face.E, Face.S));
        EntitySnapshot visual = visual(sideways, 2.0D, sideways.toWorld(Angles.direction(67.0F, -15.0F)));
        List<String> applied = new ArrayList<>();
        doAnswer(call -> {
            applied.add("pose");
            return null;
        }).when(ghost).setYRot(23.0F);
        try (MockedStatic<MinecraftGravity> gravity = mockStatic(MinecraftGravity.class)) {
            gravity.when(() -> MinecraftGravity.set(ghost, sideways)).thenAnswer(call -> {
                assertEquals(2.0D, scale.getValue(), 0.0D);
                applied.add("frame");
                return null;
            });
            ClientLevelScene.initialize(ghost, visual);
            assertEquals(List.of("frame", "pose"), applied);
            verify(ghost).setXRot(-15.0F);
            verify(ghost).setYHeadRot(ClientLevelScene.headYaw(visual));
            assertEquals(23.0F, ghost.yBodyRot, 0.0F);
            assertEquals(67.0F, ghost.yHeadRotO, 1.0E-4F);
        }
    }

    @Test
    public void aFrameUpdateRecomputesLocalHeadYawEvenWithAnUnchangedWorldLook() {
        LivingEntity ghost = mock(LivingEntity.class);
        AttributeInstance scale = new AttributeInstance(Attributes.SCALE, changed -> {
        });
        when(ghost.getAttribute(Attributes.SCALE)).thenReturn(scale);
        GravityFrame sideways = new GravityFrame(AxisPermutation.of(Face.D, Face.E, Face.S));
        Vec3d worldLook = sideways.toWorld(Angles.direction(67.0F, -15.0F));
        EntitySnapshot before = visual(GravityFrame.WORLD, 1.0D, worldLook);
        EntitySnapshot after = visual(sideways, 0.5D, worldLook);
        try (MockedStatic<MinecraftGravity> gravity = mockStatic(MinecraftGravity.class)) {
            ClientLevelScene.move(ghost, after, before);
            gravity.verify(() -> MinecraftGravity.set(ghost, sideways));
            assertEquals(0.5D, scale.getValue(), 0.0D);
            verify(ghost).lerpHeadTo(ClientLevelScene.headYaw(after), 3);
            assertEquals(67.0F, ClientLevelScene.headYaw(after), 1.0E-4F);
        }
    }

    @Test
    public void anUnchangedCachedVisualDoesNotMutateTheGhostFrameOrHead() {
        LivingEntity ghost = mock(LivingEntity.class);
        EntitySnapshot cached = visual(GravityFrame.WORLD, 1.0D, Angles.direction(23.0F, -15.0F));
        try (MockedStatic<MinecraftGravity> gravity = mockStatic(MinecraftGravity.class)) {
            ClientLevelScene.move(ghost, cached, cached);
            gravity.verifyNoInteractions();
            verifyNoInteractions(ghost);
        }
    }

    private static EntitySnapshot visual(GravityFrame gravity, double scale, Vec3d look) {
        return EntitySnapshot.full(UUID.randomUUID(), "minecraft:cow", 10.0D, 64.0D, 20.0D, 1.8D,
            look.x(), look.y(), look.z(), 23.0F, -15.0F, 0.0D, 0.0D, 0.0D, false,
            "", "", "", null, null, new EntityVisualMetadata(gravity, scale, EntitySnapshot.EMPTY).encode(), EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, 0);
    }
}
