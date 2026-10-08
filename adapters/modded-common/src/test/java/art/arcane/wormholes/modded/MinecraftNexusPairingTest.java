package art.arcane.wormholes.modded;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.portal.Portal;
import net.minecraft.server.level.ServerPlayer;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftNexusPairingTest extends MinecraftTestBase {
    @Test
    public void linkAndReturnPreservesBothFacesAfterReapplyingSavingAndSettingsSync() {
        for (String destinationWorld : List.of("minecraft:overworld", "minecraft:the_nether")) {
            WormholesModRuntime runtime = mock(WormholesModRuntime.class);
            MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
            ServerPlayer player = mock(ServerPlayer.class);
            when(runtime.portals()).thenReturn(registry);
            MinecraftPortal first = portal("minecraft:overworld", new Vec3d(0, 64, 0), Face.N);
            MinecraftPortal second = portal(destinationWorld, new Vec3d(20, 64, 0), Face.E);
            when(registry.canManage(player, first)).thenReturn(true);
            when(registry.canManage(player, second)).thenReturn(true);
            MinecraftNexus nexus = new MinecraftNexus(runtime);
            first.link(second);

            nexus.pair(player, first, second, true);
            nexus.pair(player, second, first, true);
            MinecraftPortalSyncAccess sync = new MinecraftPortalSyncAccess(runtime);
            sync.applySettings(second, sync.collectSettings(first));

            MinecraftPortal loadedFirst = MinecraftPortal.read(first.write());
            MinecraftPortal loadedSecond = MinecraftPortal.read(second.write());
            assertEquals(second.getId(), loadedFirst.getDestinationId());
            assertEquals(first.getId(), loadedSecond.getDestinationId());
            assertEquals(Boolean.TRUE, loadedFirst.setting("nexus.reciprocal"));
            assertEquals(Boolean.TRUE, loadedSecond.setting("nexus.reciprocal"));
            assertTrue(loadedFirst.isOutgoingTraversalsEnabled());
            assertTrue(loadedSecond.isOutgoingTraversalsEnabled());
            assertTrue(loadedFirst.isIncomingTraversalsEnabled());
            assertTrue(loadedSecond.isIncomingTraversalsEnabled());
            for (boolean front : new boolean[] {true, false}) {
                Similarity outward = MinecraftPortalRegistry.towardDestination(loadedFirst, loadedSecond, front);
                Similarity returning = MinecraftPortalRegistry.towardDestination(loadedSecond, loadedFirst, front);
                Vec3d point = new Vec3d(0.3D, 64.4D, 0.7D);
                Vec3d returned = returning.point(outward.point(point));
                assertEquals(point.x(), returned.x(), 1.0E-9D);
                assertEquals(point.y(), returned.y(), 1.0E-9D);
                assertEquals(point.z(), returned.z(), 1.0E-9D);
            }
        }
    }

    private static MinecraftPortal portal(String world, Vec3d position, Face direction) {
        ApertureCells geometry = new ApertureCells();
        Vec3d top = new Vec3d(position.x(), position.y() + 2.0D, position.z());
        geometry.setBlocks(List.of(position, top));
        UUID id = UUID.randomUUID();
        return new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(id, geometry.getApertureCenter(), "Paired",
            Frame.canonical(direction), true), geometry, world, Map.of("owner", id.toString(), "type", "PORTAL")));
    }
}
