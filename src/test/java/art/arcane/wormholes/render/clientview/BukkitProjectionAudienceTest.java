package art.arcane.wormholes.render.clientview;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.volmlib.projection.ProjectionViewRegistry;
import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.render.ClientViewPortalSource;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BukkitProjectionAudienceTest {
    @Test
    void publishesTheScaledVirtualEyeAndRemovesAnUnavailableView() {
        ProjectionManager previous = Wormholes.projectionManager;
        boolean spoofing = Settings.ENTITY_SPOOFING;
        int limit = Settings.MAX_SPOOFED_ENTITIES;
        try {
            Settings.ENTITY_SPOOFING = true;
            Settings.MAX_SPOOFED_ENTITIES = 24;
            ProjectionManager manager = mock(ProjectionManager.class);
            ProjectionViewRegistry registry = mock(ProjectionViewRegistry.class);
            when(manager.projectionViews()).thenReturn(registry);
            Wormholes.projectionManager = manager;
            UUID viewer = UUID.randomUUID();
            UUID portal = UUID.randomUUID();
            UUID worldId = UUID.randomUUID();
            ClientViewObserver observer = new ClientViewObserver(viewer, null);
            World world = mock(World.class);
            when(world.getUID()).thenReturn(worldId);
            ClientViewPortalSource source = mock(ClientViewPortalSource.class);
            ViewWindow window = mock(ViewWindow.class);
            when(source.destinationWorld()).thenReturn(world);
            when(source.transformFrame()).thenReturn(window);
            when(source.viewScale()).thenReturn(2.0F);
            when(window.localFrame()).thenReturn(Frame.canonical(Face.S));
            when(window.localOrigin()).thenReturn(new Vec3d(0, 0, 0));
            when(window.transform()).thenReturn(OpticTransform.translation(-96, 0, 0));
            BukkitClientViewScene.publishView(observer, portal, source, new Location(world, 8, 192, 12));
            verify(registry).publish(viewer, portal, worldId, 100.0D, 96.0D, 6.0D);
            BukkitClientViewScene.publishView(observer, portal, null, null);
            verify(registry).removeView(viewer, portal);
        } finally {
            Wormholes.projectionManager = previous;
            Settings.ENTITY_SPOOFING = spoofing;
            Settings.MAX_SPOOFED_ENTITIES = limit;
        }
    }
}
