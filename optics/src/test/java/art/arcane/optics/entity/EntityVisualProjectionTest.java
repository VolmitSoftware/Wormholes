package art.arcane.optics.entity;

import art.arcane.optics.math.Vec3;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import art.arcane.optics.volume.ViewVolume;

public final class EntityVisualProjectionTest {
    @Test
    public void paintingSnapshotUsesTheSameTransformedBlockAnchorAsLiveProjection() {
        IPortal source = mock(IPortal.class);
        Frame local = Frame.canonical(Face.E);
        Frame remote = Frame.canonical(Face.N);
        when(source.getOrigin()).thenReturn(new Vec3(0, 0, 0));
        when(source.getFrame()).thenReturn(local);
        ViewVolume frustum = mock(ViewVolume.class);
        when(frustum.containsPrimitive(anyDouble(), anyDouble(), anyDouble())).thenReturn(true);
        EntitySnapshot visual = EntitySnapshot.full(UUID.randomUUID(), "minecraft:painting", 7.0D, 2.5D, -1.5D, 2.0D,
            0, 0, -1, 0, 0, 0, 0, 0, false, "", "", "", null, null, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, 0);
        EntityVisualProjection<Object, IPortal, Vec3> projection = new EntityVisualProjection<>(Vec3::new);
        assertTrue(projection.project(source, 0, 0, 0, local, remote, frustum, visual, false, 0, null, false, true));
        assertEquals(new Vec3(1, 2, 7), projection.position());
    }
}
