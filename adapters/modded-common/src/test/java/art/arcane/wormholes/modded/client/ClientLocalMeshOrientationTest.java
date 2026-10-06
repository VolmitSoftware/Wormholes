package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.DirectionMapping;
import art.arcane.optics.math.Face;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Test;

import static org.junit.Assert.assertSame;

public class ClientLocalMeshOrientationTest extends MinecraftTestBase {
    @Test
    public void nativeMirrorSectionsRetainSourceFacesForSingleVertexReflection() throws Exception {
        BlockState original = Blocks.RED_MUSHROOM_BLOCK.defaultBlockState()
            .setValue(HugeMushroomBlock.UP, true)
            .setValue(HugeMushroomBlock.DOWN, false)
            .setValue(HugeMushroomBlock.NORTH, true)
            .setValue(HugeMushroomBlock.SOUTH, false)
            .setValue(HugeMushroomBlock.EAST, true)
            .setValue(HugeMushroomBlock.WEST, false);
        for (Face normal : Face.values()) {
            for (int quarterTurns = 0; quarterTurns < 4; quarterTurns++) {
                DirectionMapping mapping = DirectionMapping.mirror(Frame.canonical(normal), quarterTurns, new double[3]);
                Face x = mapping.map(Face.E);
                Face y = mapping.map(Face.U);
                Face z = mapping.map(Face.S);
                Vec3d translation = new Vec3d(
                    x.x() < 0 || y.x() < 0 || z.x() < 0 ? 16 : 0,
                    x.y() < 0 || y.y() < 0 || z.y() < 0 ? 16 : 0,
                    x.z() < 0 || y.z() < 0 || z.z() < 0 ? 16 : 0);
                ClientLocalMeshSourcesTest.Fixture fixture = new ClientLocalMeshSourcesTest.Fixture();
                fixture.state.set(original);
                ProjectionEnvironment.Transform transform = new ProjectionEnvironment.Transform(x, y, z, translation);
                fixture.session.handle(new ClientViewMessage.Environment(1, PortalEnvironmentTest.environment(transform)), fixture.sink);
                fixture.awaitSection();
                ClientMeshSections.Section section = fixture.session.meshes().view(1).section(0L);
                for (int cell = 0; cell < 4096; cell++) {
                    assertSame("normal=" + normal + " turns=" + quarterTurns + " cell=" + cell, original, section.state(cell));
                }
                fixture.sources.clear();
                fixture.session.clearPortals();
            }
        }
    }
}
