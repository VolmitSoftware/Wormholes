package art.arcane.wormholes;

import art.arcane.wormholes.access.AccessTestPortals;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TraversableManagerMovementTest {
    @Test
    void lookOnlyAndCancelledEventsDoNotReplaceMovementMomentum() {
        TraversableManager manager = new TraversableManager();
        World world = AccessTestPortals.world("movement");
        Player player = AccessTestPortals.player("Traveler", false, Set.of());
        Location start = new Location(world, 8.5D, 101.0D, 12.5D);
        Location end = start.clone().add(0.0D, 0.0D, -0.2D);
        manager.on(new PlayerMoveEvent(player, start, end));
        TraversableManager.Movement accepted = manager.movement(player.getUniqueId());
        Location rotated = end.clone();
        rotated.setYaw(90.0F);

        manager.on(new PlayerMoveEvent(player, end, rotated));
        PlayerMoveEvent cancelled = new PlayerMoveEvent(player, end, end.clone().add(4.0D, 0.0D, 0.0D));
        cancelled.setCancelled(true);
        manager.on(cancelled);

        assertSame(accepted, manager.movement(player.getUniqueId()));
        assertEquals(-0.2D, manager.getVelocity(player).getZ(), 1.0E-9D);
    }

    @Test
    void teleportsBreakSweepContinuityAndZeroMomentum() {
        TraversableManager manager = new TraversableManager();
        World world = AccessTestPortals.world("movement-teleport");
        Player player = AccessTestPortals.player("Traveler", false, Set.of());
        Location start = new Location(world, 8.5D, 101.0D, 12.5D);
        manager.on(new PlayerMoveEvent(player, start, start.clone().add(0.0D, 0.0D, -0.2D)));
        TraversableManager.Movement before = manager.movement(player.getUniqueId());
        PlayerTeleportEvent teleport = new PlayerTeleportEvent(player, start, start.clone().add(0.0D, 0.0D, -20.0D));
        manager.on((PlayerMoveEvent) teleport);
        assertSame(before, manager.movement(player.getUniqueId()));

        manager.on(teleport);

        assertNotEquals(before.continuity(), manager.movement(player.getUniqueId()).continuity());
        assertEquals(new Vector(), manager.getVelocity(player));
        assertEquals(-7.5D, manager.movement(player.getUniqueId()).z(), 1.0E-9D);
    }

    @Test
    void concurrentPlayerRegionsKeepIndependentLatestMovement() {
        TraversableManager manager = new TraversableManager();
        World world = AccessTestPortals.world("movement-regions");
        List<Player> players = new ArrayList<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            Player player = AccessTestPortals.player("Traveler" + index, false, Set.of());
            players.add(player);
            futures.add(CompletableFuture.runAsync(() -> {
                for (int step = 0; step < 40; step++) {
                    Location start = new Location(world, 8.5D, 101.0D, 12.5D - step * 0.2D);
                    manager.on(new PlayerMoveEvent(player, start, start.clone().add(0.0D, 0.0D, -0.2D)));
                }
            }));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        for (Player player : players) {
            assertEquals(4.5D, manager.movement(player.getUniqueId()).z(), 1.0E-9D);
            assertEquals(-0.2D, manager.getVelocity(player).getZ(), 1.0E-9D);
        }
    }
}
