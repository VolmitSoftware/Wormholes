package art.arcane.wormholes.render.clientview;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.render.ClientViewPortalSource;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class BukkitScaledViewEyeTest {
    @Test
    void environmentAndNestedEyesScaleAroundThePortalCenterBeforeRotation() {
        Vec3d origin = new Vec3d(11.5D, 65.0D, 20.5D);
        Vec3d target = new Vec3d(100.5D, 81.0D, -30.5D);
        ViewWindow window = ViewWindow.between(origin, Frame.canonical(Face.N), target, Frame.fromNormalUp(Face.E, Face.S), true, 64.0D);
        Vec3d offset = new Vec3d(1.25D, 0.5D, 6.0D);
        ClientViewPortalSource source = mock(ClientViewPortalSource.class);
        when(source.transformFrame()).thenReturn(window);
        for (float scale : new float[] {0.5F, 1.0F, 2.0F}) {
            when(source.viewScale()).thenReturn(scale);
            Vec3d eye = origin.add(offset);
            Vec3d expected = target.add(window.toward().vector(offset.multiply(1.0D / scale)));
            Vec3d actual = BukkitClientViewScene.destinationEye(source, new Location(null, eye.x(), eye.y(), eye.z()));
            assertEquals(expected.x(), actual.x(), 1.0E-9D);
            assertEquals(expected.y(), actual.y(), 1.0E-9D);
            assertEquals(expected.z(), actual.z(), 1.0E-9D);
        }
    }
}
