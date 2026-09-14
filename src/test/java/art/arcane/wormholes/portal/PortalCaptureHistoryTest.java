package art.arcane.wormholes.portal;

import art.arcane.wormholes.TraversableManager;
import art.arcane.wormholes.TraversableManager.Movement;
import art.arcane.wormholes.access.AccessTestPortals;
import art.arcane.wormholes.util.Cuboid;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalCaptureHistoryTest {
    @Test
    void batchedMovesCrossTheApertureEvenWhenTheLastPacketDoesNot() {
        Fixture fixture = new Fixture();
        PortalCaptureHistory history = fixture.history();
        Location end = fixture.moveTo(7.2D);
        history.beginPass();
        Location start = history.capture(fixture.motion(), end, 1_100L);

        assertNotNull(PortalCaptureHistory.clip(start, end, fixture.structure.getArea()));
        assertNull(PortalCaptureHistory.clip(end.clone().subtract(fixture.manager.getVelocity(fixture.player)),
            end, fixture.structure.getArea()));
        assertEquals(12.5D, start.getZ(), 1.0E-9D);
        assertEquals(-0.2D, fixture.manager.getVelocity(fixture.player).getZ(), 1.0E-9D);
    }

    @Test
    void separatePortalsDoNotConsumeEachOthersMovementHistory() {
        Fixture fixture = new Fixture();
        PortalCaptureHistory first = fixture.history();
        PortalCaptureHistory second = fixture.history();
        Location intermediate = fixture.moveTo(10.0D);
        first.beginPass();
        first.capture(fixture.motion(), intermediate, 1_050L);
        Location end = fixture.moveTo(7.2D);
        first.beginPass();
        second.beginPass();

        assertEquals(10.0D, first.capture(fixture.motion(), end, 1_100L).getZ(), 1.0E-9D);
        assertEquals(12.5D, second.capture(fixture.motion(), end, 1_100L).getZ(), 1.0E-9D);
    }

    @Test
    void leavingTheWholeCaptureZoneRetainsOneBoundedCrossing() {
        Fixture fixture = new Fixture();
        PortalCaptureHistory history = fixture.history();
        fixture.moveTo(-12.0D);
        history.beginPass();

        List<PortalCaptureHistory.Pending> pending = history.departed(fixture.manager, fixture.structure, 1_100L);

        assertEquals(1, pending.size());
        assertEquals(fixture.player.getUniqueId(), pending.getFirst().playerId());
        assertTrue(pending.getFirst().stillContinuous(fixture.motion(), 1_100L));
        assertFalse(pending.getFirst().stillContinuous(fixture.motion(), 7_000L));
        assertTrue(history.departed(fixture.manager, fixture.structure, 1_100L).isEmpty());
        Location current = fixture.motion().location(fixture.world);
        fixture.manager.on(new PlayerTeleportEvent(fixture.player, current, fixture.start));
        assertFalse(pending.getFirst().stillContinuous(fixture.motion(), 1_100L));
    }

    @Test
    void closedPortalInvalidatesQueuedAndUnconsumedCrossings() {
        Fixture fixture = new Fixture();
        PortalCaptureHistory history = fixture.history();
        fixture.moveTo(-12.0D);
        history.beginPass();
        PortalCaptureHistory.Pending pending = history.departed(fixture.manager, fixture.structure, 1_100L).getFirst();

        history.invalidate();

        assertFalse(pending.stillContinuous(fixture.motion(), 1_100L));
        history.beginPass();
        assertNull(history.capture(fixture.motion(), fixture.motion().location(fixture.world), 1_200L));
        fixture.moveTo(12.5D);
        history.invalidate();
        history.beginPass();
        assertNull(history.capture(fixture.motion(), fixture.start, 1_300L));
    }

    @Test
    void teleportAndExpiredCapturesCannotSynthesizeCrossings() {
        Fixture fixture = new Fixture();
        PortalCaptureHistory history = fixture.history();
        Location end = fixture.start.clone().subtract(0.0D, 0.0D, 20.0D);
        fixture.manager.on(new PlayerTeleportEvent(fixture.player, fixture.start, end));
        history.beginPass();
        assertNull(history.capture(fixture.motion(), end, 1_100L));

        fixture.moveTo(12.5D);
        history.beginPass();
        assertNull(history.capture(fixture.motion(), fixture.start, 7_000L));
    }

    @Test
    void replacementSessionAndInProgressTeleportDiscardThePreviousSweep() {
        Fixture fixture = new Fixture();
        PortalCaptureHistory history = fixture.history();
        Location target = fixture.start.clone().subtract(0.0D, 0.0D, 20.0D);
        fixture.manager.on(new PlayerTeleportEvent(fixture.player, fixture.start, target));
        history.beginPass();
        assertNull(history.capture(fixture.motion(), fixture.start, 1_100L));
        history.beginPass();
        assertNull(history.capture(fixture.motion(), target, 1_200L));

        Player replacement = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
            (instance, method, arguments) -> method.invoke(fixture.player, arguments));
        Movement replacementMotion = fixture.manager.movement(replacement, fixture.start);
        history.beginPass();
        assertNull(history.capture(replacementMotion, fixture.start, 1_300L));
    }

    private static final class Fixture {
        private final World world = AccessTestPortals.world("capture-history");
        private final Player player = AccessTestPortals.player("Traveler", false, Set.of());
        private final Location start = new Location(world, 8.5D, 101.0D, 12.5D);
        private final TraversableManager manager = new TraversableManager();
        private final PortalStructure structure = new PortalStructure();

        private Fixture() {
            structure.setWorld(world);
            structure.setArea(new Cuboid(new Location(world, 7.0D, 101.0D, 8.0D),
                new Location(world, 9.0D, 104.0D, 8.0D)));
            manager.movement(player, start);
        }

        private PortalCaptureHistory history() {
            PortalCaptureHistory history = new PortalCaptureHistory();
            history.beginPass();
            history.capture(motion(), start, 1_000L);
            return history;
        }

        private Movement motion() {
            return manager.movement(player.getUniqueId());
        }

        private Location moveTo(double z) {
            double startZ = motion().z();
            double direction = z < startZ ? -1.0D : 1.0D;
            while (Math.abs(z - startZ) > 0.2D) {
                Location from = new Location(world, 8.5D, 101.0D, startZ);
                Location to = from.clone().add(0.0D, 0.0D, direction * 0.2D);
                manager.on(new PlayerMoveEvent(player, from, to));
                startZ = to.getZ();
            }
            Location from = new Location(world, 8.5D, 101.0D, z - direction * 0.2D);
            Location to = new Location(world, 8.5D, 101.0D, z);
            manager.on(new PlayerMoveEvent(player, from, to));
            return to;
        }
    }
}
