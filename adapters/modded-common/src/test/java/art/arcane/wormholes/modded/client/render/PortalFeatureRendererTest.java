package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.util.Direction;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.joml.Vector3f;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PortalFeatureRendererTest {
    @Test
    public void nativeEntitiesAndSelfReflectionsRotateTheirBodyUpExactlyOnce() {
        EntityRenderState state = new EntityRenderState();
        state.x = -12.5D;
        state.y = 64;
        state.z = 30.5D;
        for (Direction[] axes : new Direction[][] {{Direction.U, Direction.W, Direction.S}, {Direction.D, Direction.E, Direction.S},
            {Direction.D, Direction.W, Direction.N}, {Direction.U, Direction.E, Direction.N}}) {
            ClientViewEnvironment.Transform transform = new ClientViewEnvironment.Transform(axes[0], axes[1], axes[2],
                new GeometryVector(100.5D, -20.25D, 50));
            PoseStack pose = new PoseStack();
            PortalFeatureRenderer.entityPose(pose, state, new Vec3(1, 2, 3), transform);
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
    public void nativeBlockEntityCoordinatesAndLocalModelVerticesShareOneWorldTransform() {
        PoseStack pose = new PoseStack();
        ClientViewEnvironment.Transform transform = new ClientViewEnvironment.Transform(Direction.U, Direction.W, Direction.S,
            new GeometryVector(100, 50, -20));
        PortalFeatureRenderer.blockPose(pose, new BlockPos(12, 30, 40), new Vec3(3, 4, 5), transform);
        assertEquals(new Vector3f(67, 58, 15), pose.last().pose().transformPosition(new Vector3f()));
        assertEquals(new Vector3f(66.5F, 59, 15.25F), pose.last().pose().transformPosition(new Vector3f(1, 0.5F, 0.25F)));
    }

    @Test
    public void mirroredBlockEntitiesReflectAroundTheirDestinationWorldPosition() {
        PoseStack pose = new PoseStack();
        ClientViewEnvironment.Transform transform = new ClientViewEnvironment.Transform(Direction.W, Direction.U, Direction.S,
            new GeometryVector(100, 50, -20));
        PortalFeatureRenderer.blockPose(pose, new BlockPos(12, 30, 40), new Vec3(3, 4, 5), transform);
        assertEquals(new Vector3f(85, 76, 15), pose.last().pose().transformPosition(new Vector3f()));
        assertEquals(new Vector3f(84, 76.5F, 15.25F), pose.last().pose().transformPosition(new Vector3f(1, 0.5F, 0.25F)));
    }
}
