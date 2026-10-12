package art.arcane.wormholes.modded;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.gravity.GravityHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;

import static org.mockito.Mockito.when;

public abstract class MinecraftTestBase {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (Block.BLOCK_STATE_REGISTRY.getId(state) < 0) {
                    Block.BLOCK_STATE_REGISTRY.add(state);
                }
            }
        }
    }
    protected static Vec3 lookVector(float yaw, float pitch) {
        Vec3d direction = Angles.direction(yaw, pitch);
        return new Vec3(direction.x(), direction.y(), direction.z());
    }

    protected static <T extends Entity> T worldGravity(T entity) {
        if (entity instanceof GravityHolder holder) {
            when(holder.wormholesGravity()).thenReturn(GravityFrame.WORLD);
        }
        return entity;
    }

}
