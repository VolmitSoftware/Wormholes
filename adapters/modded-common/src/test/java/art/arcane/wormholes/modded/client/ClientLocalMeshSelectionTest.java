package art.arcane.wormholes.modded.client;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.AxisPermutation;
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
        Vec3d eye = new Vec3d(4.5, 12.25, -3.75);
        assertEquals(eye, ClientLocalMeshSources.sourceEye(session, root, eye));
    }

    @Test
    public void nestedSelectionReflectsTheEyeThroughItsParentOnly() {
        ClientViewSession session = mock(ClientViewSession.class);
        ClientPortal root = portal(1, 0);
        ClientPortal child = portal(2, 1);
        when(session.portal(1)).thenReturn(root);
        OpticTransform reflection = OpticTransform.of(AxisPermutation.of(Face.E, Face.U, Face.N), 0, 0, 10);
        ProjectionEnvironment environment = PortalEnvironmentTest.environment(reflection);
        when(session.environment(1)).thenReturn(environment);
        assertEquals(new Vec3d(2.5, 3.25, -4.75),
            ClientLocalMeshSources.sourceEye(session, child, new Vec3d(2.5, 3.25, 14.75)));
    }

    @Test
    public void rotatedAndReflectedAncestorsApplyFromRootInwardAndTrackCameraMovement() {
        ClientViewSession session = mock(ClientViewSession.class);
        ClientPortal root = portal(1, 0);
        ClientPortal parent = portal(2, 1);
        ClientPortal child = portal(3, 2);
        when(session.portal(1)).thenReturn(root);
        when(session.portal(2)).thenReturn(parent);
        ProjectionEnvironment rootEnvironment = PortalEnvironmentTest.environment(OpticTransform.of(AxisPermutation.of(Face.S, Face.U, Face.W), 100, 20, -50));
        ProjectionEnvironment parentEnvironment = PortalEnvironmentTest.environment(OpticTransform.of(AxisPermutation.of(Face.W, Face.U, Face.S), 6, 0, 0));
        when(session.environment(1)).thenReturn(rootEnvironment);
        when(session.environment(2)).thenReturn(parentEnvironment);
        assertEquals(new Vec3d(2, 3, 4),
            ClientLocalMeshSources.sourceEye(session, child, new Vec3d(96, 23, -46)));
        assertEquals(new Vec3d(0, 4, 6),
            ClientLocalMeshSources.sourceEye(session, child, new Vec3d(94, 24, -44)));
    }

    @Test
    public void missingOrCyclicAncestryCannotSelectAnUnrelatedSourceFootprint() {
        ClientViewSession session = mock(ClientViewSession.class);
        ClientPortal child = portal(3, 2);
        Vec3d eye = new Vec3d(4, 5, 6);
        assertNull(ClientLocalMeshSources.sourceEye(session, child, eye));
        ClientPortal parent = portal(2, 0);
        when(session.portal(2)).thenReturn(parent);
        assertNull(ClientLocalMeshSources.sourceEye(session, child, eye));
        ProjectionEnvironment environment = PortalEnvironmentTest.environment(OpticTransform.IDENTITY);
        when(session.environment(2)).thenReturn(environment);
        parent = portal(2, 3);
        when(session.portal(2)).thenReturn(parent);
        when(session.portal(3)).thenReturn(child);
        when(session.environment(3)).thenReturn(environment);
        assertNull(ClientLocalMeshSources.sourceEye(session, child, eye));
    }

    private static ClientPortal portal(int key, int parent) {
        ClientPortal portal = mock(ClientPortal.class);
        ApertureDescriptor geometry = mock(ApertureDescriptor.class);
        when(portal.portalKey()).thenReturn(key);
        when(portal.geometry()).thenReturn(geometry);
        when(geometry.parentPortalKey()).thenReturn(parent);
        return portal;
    }
}
