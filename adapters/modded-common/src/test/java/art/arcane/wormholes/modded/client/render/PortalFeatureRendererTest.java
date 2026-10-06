package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.math.Face;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PortalFeatureRendererTest {
    @Test
    public void entitiesAndBlockEntitiesReuseTheSameUnchangedRotation() {
        ProjectionEnvironment.Transform transform = new ProjectionEnvironment.Transform(Face.U, Face.W, Face.S,
            new Vec3d(100, 50, -20));
        Matrix4f rotation = PortalProjection.rotation(transform);
        Matrix4f original = new Matrix4f(rotation);
        EntityRenderState entity = new EntityRenderState();
        entity.x = 12;
        entity.y = 30;
        entity.z = 40;
        PoseStack entityPose = new PoseStack();
        PoseStack blockPose = new PoseStack();
        Vec3 eye = new Vec3(3, 4, 5);
        PortalFeatureRenderer.entityPose(entityPose, entity, eye, transform, rotation);
        PortalFeatureRenderer.blockPose(blockPose, new BlockPos(12, 30, 40), eye, transform, rotation);
        assertEquals(original, rotation);
        assertEquals(entityPose.last().pose(), blockPose.last().pose());
        assertEquals(entityPose.last().normal(), blockPose.last().normal());
    }

    @Test
    public void nativeEntitiesAndSelfReflectionsRotateTheirBodyUpExactlyOnce() {
        EntityRenderState state = new EntityRenderState();
        state.x = -12.5D;
        state.y = 64;
        state.z = 30.5D;
        for (Face[] axes : new Face[][] {{Face.U, Face.W, Face.S}, {Face.D, Face.E, Face.S},
            {Face.D, Face.W, Face.N}, {Face.U, Face.E, Face.N}}) {
            ProjectionEnvironment.Transform transform = new ProjectionEnvironment.Transform(axes[0], axes[1], axes[2],
                new Vec3d(100.5D, -20.25D, 50));
            PoseStack pose = new PoseStack();
            PortalFeatureRenderer.entityPose(pose, state, new Vec3(1, 2, 3), transform, PortalProjection.rotation(transform));
            Vector3f feet = pose.last().pose().transformPosition(new Vector3f());
            Vector3f head = pose.last().pose().transformPosition(new Vector3f(0, 1.8F, 0));
            assertEquals(axes[1].x() * 1.8F, head.x - feet.x, 0.00001F);
            assertEquals(axes[1].y() * 1.8F, head.y - feet.y, 0.00001F);
            assertEquals(axes[1].z() * 1.8F, head.z - feet.z, 0.00001F);
            assertEquals(state.x * axes[0].x() + state.y * axes[1].x() + state.z * axes[2].x() + 99.5D, feet.x, 0.00001D);
            assertEquals(state.x * axes[0].y() + state.y * axes[1].y() + state.z * axes[2].y() - 22.25D, feet.y, 0.00001D);
            assertEquals(state.x * axes[0].z() + state.y * axes[1].z() + state.z * axes[2].z() + 47, feet.z, 0.00001D);
        }
    }

    @Test
    public void blockEntityPositionsAndLocalModelVerticesUseDestinationRotationAndReflection() {
        for (BlockPose example : new BlockPose[] {
            new BlockPose(Face.U, Face.W, new Vector3f(67, 58, 15), new Vector3f(66.5F, 59, 15.25F)),
            new BlockPose(Face.W, Face.U, new Vector3f(85, 76, 15), new Vector3f(84, 76.5F, 15.25F))
        }) {
            PoseStack pose = new PoseStack();
            ProjectionEnvironment.Transform transform = new ProjectionEnvironment.Transform(example.xAxis(), example.yAxis(), Face.S,
                new Vec3d(100, 50, -20));
            PortalFeatureRenderer.blockPose(pose, new BlockPos(12, 30, 40), new Vec3(3, 4, 5), transform, PortalProjection.rotation(transform));
            assertEquals(example.origin(), pose.last().pose().transformPosition(new Vector3f()));
            assertEquals(example.vertex(), pose.last().pose().transformPosition(new Vector3f(1, 0.5F, 0.25F)));
        }
    }

    private record BlockPose(Face xAxis, Face yAxis, Vector3f origin, Vector3f vertex) { }
}
