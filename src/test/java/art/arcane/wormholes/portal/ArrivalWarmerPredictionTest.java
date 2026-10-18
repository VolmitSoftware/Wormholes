package art.arcane.wormholes.portal;

import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.access.AccessTestPortals;
import art.arcane.wormholes.TraversableManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.util.BukkitGeometry;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ArrivalWarmerPredictionTest {
    @Test
    void approachUsesTheCrossingSideAndVelocityAtChunkBoundaries() {
        World world = AccessTestPortals.world("arrival-prediction");
        LocalPortal destination = AccessTestPortals.portal(world);
        destination.setFrame(Frame.canonical(Face.S));
        ILocalPortal source = mock(ILocalPortal.class);
        when(source.getFrame()).thenReturn(Frame.canonical(Face.S));
        when(source.getOrigin()).thenReturn(new Vec3d(0.5, 65.5, 15.5));
        TraversableManager previous = Wormholes.traversableManager;
        try {
            for (boolean recordedMovement : new boolean[]{false, true}) {
                Wormholes.traversableManager = recordedMovement ? new TraversableManager() : null;
                for (double sign : new double[]{-1.0D, 1.0D}) {
                    Player player = mock(Player.class);
                    Location start = new Location(world, 0.5, 64.0, 15.5 + sign * 0.1);
                    Vector velocity = new Vector(0, 0, -sign * 2.0D);
                    Vector end = start.toVector().add(velocity);
                    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
                    when(player.getLocation()).thenReturn(start);
                    when(player.getVelocity()).thenReturn(recordedMovement ? new Vector() : velocity);
                    if (recordedMovement) {
                        Wormholes.traversableManager.on(new PlayerMoveEvent(player, start.clone().subtract(velocity), start));
                    }
                    when(player.getBoundingBox()).thenReturn(new BoundingBox(0.2, 64, start.getZ() - 0.3,
                        0.8, 65.8, start.getZ() + 0.3));
                    PlaneCrossing crossing = PlaneCrossing.create(source.getFrame(), source.getOrigin(),
                        new PlaneCrossing.Motion(BukkitGeometry.vector(start), BukkitGeometry.vector(end),
                            BukkitGeometry.vector(velocity), BukkitGeometry.vector(start.getDirection())));
                    Traversive traversive = new Traversive(player, TraversableType.ENTITY, crossing.frame(),
                        BukkitGeometry.bukkit(crossing.origin()), end, velocity, start.getDirection(), crossing.frontSide(), null);
                    Location actual = destination.computeExitTarget(traversive);
                    Location predicted = ArrivalWarmer.predictedArrival(source, destination, player);
                    assertEquals(actual.getBlockX() >> 4, predicted.getBlockX() >> 4);
                    assertEquals(actual.getBlockZ() >> 4, predicted.getBlockZ() >> 4);
                    assertEquals(actual.getY(), predicted.getY(), 1.0E-9D);
                }
            }
        } finally {
            Wormholes.traversableManager = previous;
        }
    }
}
