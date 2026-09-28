package art.arcane.wormholes.render;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class EntityVisualProjectionTest {
    @Test
    public void paintingSnapshotUsesTheSameTransformedBlockAnchorAsLiveProjection() {
        IPortal source = mock(IPortal.class);
        PortalFrame local = PortalFrame.canonical(Direction.E);
        PortalFrame remote = PortalFrame.canonical(Direction.N);
        when(source.getOrigin()).thenReturn(new GeometryVector(0, 0, 0));
        when(source.getFrame()).thenReturn(local);
        Frustum4D frustum = mock(Frustum4D.class);
        when(frustum.containsPrimitive(anyDouble(), anyDouble(), anyDouble())).thenReturn(true);
        EntityVisual visual = EntityVisual.full(UUID.randomUUID(), "minecraft:painting", 7.0D, 2.5D, -1.5D, 2.0D,
            0, 0, -1, 0, 0, 0, 0, 0, false, "", "", "", null, null, EntityVisual.EMPTY, EntityVisual.EMPTY, EntityVisual.EMPTY, 0);
        EntityVisualProjection<Object, IPortal, GeometryVector> projection = new EntityVisualProjection<>(GeometryVector::new);
        assertTrue(projection.project(source, 0, 0, 0, local, remote, frustum, visual, false, 0, null, false, true));
        assertEquals(new GeometryVector(1, 2, 7), projection.position());
    }
}
