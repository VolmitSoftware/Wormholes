package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.PortalScene;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.util.Direction;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Camera;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyFloat;

public class ClientMeshViewsTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void failedFeatureExtractionRetainsTheSceneAndWaitsForRendererRecovery() {
        ClientViewSession session = mock(ClientViewSession.class);
        ClientMeshSections meshes = mock(ClientMeshSections.class);
        ClientMeshSections.View view = mock(ClientMeshSections.View.class);
        ClientPortal portal = mock(ClientPortal.class);
        ClientPortalGeometry geometry = mock(ClientPortalGeometry.class);
        ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
        ClientLevel level = mock(ClientLevel.class);
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        Camera camera = mock(Camera.class);
        when(session.active()).thenReturn(true);
        when(session.meshes()).thenReturn(meshes);
        when(meshes.view(7)).thenReturn(view);
        when(view.changed()).thenReturn(new LongOpenHashSet());
        when(portal.portalKey()).thenReturn(7);
        when(portal.geometry()).thenReturn(geometry);
        when(session.portal(7)).thenReturn(portal);
        Int2ObjectOpenHashMap<ClientPortal> portals = new Int2ObjectOpenHashMap<>();
        portals.put(7, portal);
        when(session.portals()).thenReturn(portals);
        when(session.environment(7)).thenReturn(environment);
        when(environment.transform()).thenReturn(new ClientViewEnvironment.Transform(Direction.E, Direction.U, Direction.S,
            new GeometryVector(0, 0, 0)));
        when(renderer.available(7)).thenReturn(true);
        IllegalStateException failure = new IllegalStateException("Feature renderer unavailable");
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
             MockedConstruction<ClientMeshEntities> features = mockConstruction(ClientMeshEntities.class,
                 (entities, context) -> doThrow(failure).when(entities).extract(eq(7), any(Camera.class), anyFloat(), any()))) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(renderer);
            ClientMeshViews views = new ClientMeshViews();
            views.update(session, level);
            views.extract(camera, 0.5F);
            verify(renderer).featureFailed(7, failure);
            when(renderer.available(7)).thenReturn(false);
            views.update(session, level);
            views.extract(camera, 0.5F);
            verify(features.constructed().getFirst(), times(1)).extract(eq(7), any(Camera.class), anyFloat(), any());
            verify(renderer, times(1)).replaceScene(eq(7), any(PortalScene.class));
            verify(renderer, never()).remove(7);
        }
    }

    @Test
    public void changedDestinationTransformReplacesTheSceneEvenWithIdenticalGeometryAndSectionGeneration() {
        ClientViewSession session = mock(ClientViewSession.class);
        ClientMeshSections meshes = mock(ClientMeshSections.class);
        ClientMeshSections.View view = mock(ClientMeshSections.View.class);
        ClientPortal portal = mock(ClientPortal.class);
        ClientPortalGeometry geometry = mock(ClientPortalGeometry.class);
        ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
        ClientLevel level = mock(ClientLevel.class);
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        when(session.active()).thenReturn(true);
        when(session.meshes()).thenReturn(meshes);
        when(meshes.view(7)).thenReturn(view);
        when(view.changed()).thenReturn(new LongOpenHashSet());
        when(portal.portalKey()).thenReturn(7);
        when(portal.geometry()).thenReturn(geometry);
        when(session.portal(7)).thenReturn(portal);
        Int2ObjectOpenHashMap<ClientPortal> portals = new Int2ObjectOpenHashMap<>();
        portals.put(7, portal);
        when(session.portals()).thenReturn(portals);
        when(session.environment(7)).thenReturn(environment);
        when(environment.transform()).thenReturn(new ClientViewEnvironment.Transform(Direction.E, Direction.U, Direction.S,
            new GeometryVector(0, 0, 0)));
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class)) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(renderer);
            ClientMeshViews views = new ClientMeshViews();
            views.update(session, level);
            views.update(session, level);
            verify(renderer, times(1)).replaceScene(eq(7), any(PortalScene.class));
            when(environment.transform()).thenReturn(new ClientViewEnvironment.Transform(Direction.U, Direction.W, Direction.S,
                new GeometryVector(0, 0, 0)));
            views.update(session, level);
            views.update(session, level);
            verify(renderer, times(2)).replaceScene(eq(7), any(PortalScene.class));
            when(session.environment(7)).thenReturn(null);
            views.update(session, level);
            verify(renderer, times(3)).replaceScene(eq(7), any(PortalScene.class));
        }
    }
}
