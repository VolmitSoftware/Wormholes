package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.iris.IrisMeshCamera;
import art.arcane.wormholes.modded.client.render.stencil.PortalSurface;
import art.arcane.wormholes.modded.client.render.stencil.PortalViewSelection;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClientPortalRendererStreamSelectionTest extends MinecraftTestBase {
    @Test
    public void changingInvisibleCandidatesNeverChangeRealNestedDemand() throws ReflectiveOperationException {
        try (Scene scene = new Scene()) {
            scene.renderer.replaceScene(17, scene.portal(geometry(0, 0, 0, Face.S)));
            scene.renderer.replaceScene(18, scene.portal(geometry(0, -4, 17, Face.S)));
            scene.renderer.replaceScene(19, scene.portal(geometry(0, -8, 18, Face.S)));
            List<IrisMeshCamera> selected = scene.selected(Set.of());
            assertEquals(2, selected.size());
            assertEquals(2, selected.getFirst().traversal().size());
            assertEquals(3, selected.getLast().traversal().size());
            for (int index = 0; index < 40; index++) {
                scene.renderer.replaceScene(20, scene.portal(geometry(100 + index, -4, 17, Face.S)));
                scene.renderer.replaceScene(21, scene.portal(geometry(0, -4, 17, Face.N)));
                scene.renderer.replaceScene(22, scene.portal(geometry(0, 2, 17, Face.S)));
                assertEquals(selected, scene.selected(Set.of()));
            }
        }
    }

    @Test
    public void nativeClaimsUnavailableOwnersAndDisjointAncestorCoverageDoNotBecomeStreamedDemand() throws ReflectiveOperationException {
        try (Scene scene = new Scene()) {
            scene.renderer.replaceScene(17, scene.portal(geometry(0, 0, 0, Face.S)));
            PortalScene child = scene.portal(geometry(0, -4, 17, Face.S));
            scene.renderer.replaceScene(18, child);
            assertEquals(1, scene.selected(Set.of()).size());
            assertTrue(scene.selected(Set.of(18)).isEmpty());
            when(child.sourceExpired()).thenReturn(true);
            assertTrue(scene.selected(Set.of()).isEmpty());
            when(child.sourceExpired()).thenReturn(false);
            PortalViewSelection.Camera camera = scene.selection.camera();
            scene.selection = new ClientPortalRenderer.StreamSelection(new PortalViewSelection.Camera(camera.eye(), camera.view(),
                camera.projection(), camera.frustum(), new PortalViewSelection.Window(0.8D, 0.8D, 1.0D, 1.0D), camera.exit(), false), 1920, 1080);
            assertTrue(scene.selected(Set.of()).isEmpty());
            scene.selection = scene.fullSelection();
            Field pipelines = ClientPortalRenderer.class.getDeclaredField("pipelines");
            pipelines.setAccessible(true);
            pipelines.set(scene.renderer, null);
            assertTrue(scene.selected(Set.of()).isEmpty());
        }
    }

    private static ApertureDescriptor geometry(int x, int z, int parent, Face facing) {
        return new ApertureDescriptor(x, 0, z, facing.ordinal(), true, 0, false, 2, 2, new long[]{15L}, ShapeDescriptor.FULL,
            0.0F, 0.0F, 0.0F, 16, 4, 0, 0, 0, 0, 0, 0, 0.0D, parent, 1L, List.of());
    }

    private static final class Scene implements AutoCloseable {
        private final ClientLevel home = mock(ClientLevel.class);
        private final EnvironmentState environment = mock(EnvironmentState.class);
        private final ClientPortalRenderer renderer;
        private ClientPortalRenderer.StreamSelection selection;

        private Scene() throws ReflectiveOperationException {
            Constructor<ClientPortalRenderer> constructor = ClientPortalRenderer.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            renderer = constructor.newInstance();
            Field pipelines = ClientPortalRenderer.class.getDeclaredField("pipelines");
            pipelines.setAccessible(true);
            pipelines.set(renderer, mock(PortalPipelines.class));
            when(home.dimension()).thenReturn(Level.OVERWORLD);
            EnvironmentState.World world = mock(EnvironmentState.World.class);
            when(world.dimensionKey()).thenReturn("minecraft:overworld");
            when(environment.world()).thenReturn(world);
            when(environment.transform()).thenReturn(OpticTransform.IDENTITY);
            when(environment.scale()).thenReturn(1.0F);
            selection = fullSelection();
        }

        private PortalScene portal(ApertureDescriptor geometry) {
            PortalScene scene = mock(PortalScene.class);
            when(scene.geometry()).thenReturn(geometry);
            when(scene.sourceLevel()).thenReturn(home);
            when(scene.environment()).thenReturn(environment);
            return scene;
        }

        private ClientPortalRenderer.StreamSelection fullSelection() {
            PortalViewSelection.Camera camera = new PortalViewSelection.Camera(new Vec3d(1.0D, 1.0D, 4.0D), new Matrix4f(),
                new Matrix4f().setPerspective((float) Math.toRadians(90.0D), 1.0F, 200.0F, 0.05F, false), null,
                PortalViewSelection.Window.FULL, PortalSurface.of(geometry(0, 0, 0, Face.S)).through(Similarity.IDENTITY), false);
            return new ClientPortalRenderer.StreamSelection(camera, 1920, 1080);
        }

        private List<IrisMeshCamera> selected(Set<Integer> nativeChildren) {
            return renderer.streamedCameras(17, home, 1, 4, nativeChildren, selection);
        }

        @Override
        public void close() {
            renderer.clear();
        }
    }
}
