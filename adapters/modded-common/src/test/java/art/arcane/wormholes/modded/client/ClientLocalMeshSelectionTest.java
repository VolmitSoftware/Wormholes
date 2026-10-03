package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.util.Direction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClientLocalMeshSelectionTest {
    @Test
    public void rootSelectionUsesActualCameraPositionWithoutApplyingItsOwnDestinationTransform() {
        ClientViewSession session = mock(ClientViewSession.class);
        ClientPortal root = portal(1, 0);
        GeometryVector eye = new GeometryVector(4.5, 12.25, -3.75);
        assertEquals(eye, ClientLocalMeshSources.sourceEye(session, root, eye));
    }

    @Test
    public void nestedSelectionReflectsTheEyeThroughItsParentOnly() {
        ClientViewSession session = mock(ClientViewSession.class);
        ClientPortal root = portal(1, 0);
        ClientPortal child = portal(2, 1);
        when(session.portal(1)).thenReturn(root);
        ClientViewEnvironment.Transform reflection = new ClientViewEnvironment.Transform(Direction.E, Direction.U, Direction.N,
            new GeometryVector(0, 0, 10));
        ClientViewEnvironment environment = PortalEnvironmentTest.environment(reflection);
        when(session.environment(1)).thenReturn(environment);
        assertEquals(new GeometryVector(2.5, 3.25, -4.75),
            ClientLocalMeshSources.sourceEye(session, child, new GeometryVector(2.5, 3.25, 14.75)));
    }

    @Test
    public void rotatedAndReflectedAncestorsApplyFromRootInwardAndTrackCameraMovement() {
        ClientViewSession session = mock(ClientViewSession.class);
        ClientPortal root = portal(1, 0);
        ClientPortal parent = portal(2, 1);
        ClientPortal child = portal(3, 2);
        when(session.portal(1)).thenReturn(root);
        when(session.portal(2)).thenReturn(parent);
        ClientViewEnvironment rootEnvironment = PortalEnvironmentTest.environment(new ClientViewEnvironment.Transform(
            Direction.S, Direction.U, Direction.W, new GeometryVector(100, 20, -50)));
        ClientViewEnvironment parentEnvironment = PortalEnvironmentTest.environment(new ClientViewEnvironment.Transform(
            Direction.W, Direction.U, Direction.S, new GeometryVector(6, 0, 0)));
        when(session.environment(1)).thenReturn(rootEnvironment);
        when(session.environment(2)).thenReturn(parentEnvironment);
        assertEquals(new GeometryVector(2, 3, 4),
            ClientLocalMeshSources.sourceEye(session, child, new GeometryVector(96, 23, -46)));
        assertEquals(new GeometryVector(0, 4, 6),
            ClientLocalMeshSources.sourceEye(session, child, new GeometryVector(94, 24, -44)));
    }

    @Test
    public void missingOrCyclicAncestryCannotSelectAnUnrelatedSourceFootprint() {
        ClientViewSession session = mock(ClientViewSession.class);
        ClientPortal child = portal(3, 2);
        GeometryVector eye = new GeometryVector(4, 5, 6);
        assertNull(ClientLocalMeshSources.sourceEye(session, child, eye));
        ClientPortal parent = portal(2, 0);
        when(session.portal(2)).thenReturn(parent);
        assertNull(ClientLocalMeshSources.sourceEye(session, child, eye));
        ClientViewEnvironment environment = PortalEnvironmentTest.environment(ClientViewEnvironment.Transform.IDENTITY);
        when(session.environment(2)).thenReturn(environment);
        parent = portal(2, 3);
        when(session.portal(2)).thenReturn(parent);
        when(session.portal(3)).thenReturn(child);
        when(session.environment(3)).thenReturn(environment);
        assertNull(ClientLocalMeshSources.sourceEye(session, child, eye));
    }

    private static ClientPortal portal(int key, int parent) {
        ClientPortal portal = mock(ClientPortal.class);
        ClientPortalGeometry geometry = mock(ClientPortalGeometry.class);
        when(portal.portalKey()).thenReturn(key);
        when(portal.geometry()).thenReturn(geometry);
        when(geometry.parentPortalKey()).thenReturn(parent);
        return portal;
    }
}
