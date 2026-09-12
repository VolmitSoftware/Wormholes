package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.transit.TransitTestSupport.Rig;

final class ConvoyLocalTraversalTest {
    @Test
    void membersDismountTeleportInOrderThenRemountAndReleashOnTheDestination() {
        World world = TransitTestSupport.world("convoy-local");
        LocalPortal source = TransitTestSupport.portal(world);
        LocalPortal destination = TransitTestSupport.portal(world,
            new Location(world, 40.0D, 64.0D, 40.0D), new Location(world, 40.0D, 66.0D, 42.0D));
        Rig boat = Rig.vehicle("boat", new Location(world, 1.0D, 65.0D, 1.0D), 1.375D, 0.5625D);
        Rig driver = Rig.player("driver", new Location(world, 1.0D, 65.0D, 1.0D)).ride(boat);
        Rig horse = Rig.mob("horse", new Location(world, 1.0D, 65.0D, 3.0D), 1.4D, 1.6D).leashTo(driver);
        ConvoyGraph graph = ConvoyGraph.closure(driver.entity(), List.of(boat.entity(), driver.entity(), horse.entity()), 16);
        RecordingTeleporter teleporter = new RecordingTeleporter(true);
        List<Entity> settled = new ArrayList<Entity>();
        AtomicInteger completed = new AtomicInteger();
        AtomicReference<String> failure = new AtomicReference<String>();

        new ConvoyLocalTraversal(teleporter).teleport(graph, destination, crossing(source, driver.entity()), new ConvoyLocalTraversal.Arrival() {
            @Override
            public void settle(Entity member, Traversive memberTraversive, boolean reloadExpected) {
                settled.add(member);
                assertSame(member, memberTraversive.getObject());
                assertFalse(reloadExpected);
            }

            @Override
            public void completed() {
                completed.incrementAndGet();
            }

            @Override
            public void failed(String reason) {
                failure.set(reason);
            }
        });

        assertEquals(List.of(
            "dismount horse", "unleash horse", "dismount driver", "dismount boat",
            "teleport boat", "teleport driver", "teleport horse",
            "region", "mount boat<-driver", "leash horse<-driver"), teleporter.log);
        assertEquals(List.of(boat.entity(), driver.entity(), horse.entity()), settled);
        assertEquals(1, completed.get());
        assertNull(failure.get());
        assertEquals(1, boat.teleports().size());
        Location boatTarget = boat.teleports().getFirst();
        Location horseTarget = horse.teleports().getFirst();
        assertEquals(2.0D, horseTarget.getZ() - boatTarget.getZ(), 1e-9D, "relative offsets survive the frame transform");
        assertTrue(boatTarget.getX() > 30.0D, "members land at the destination portal");
    }

    @Test
    void aFailedMemberTeleportRollsEveryMovedMemberBackAndReportsFailure() {
        World world = TransitTestSupport.world("convoy-rollback");
        LocalPortal source = TransitTestSupport.portal(world);
        LocalPortal destination = TransitTestSupport.portal(world,
            new Location(world, 40.0D, 64.0D, 40.0D), new Location(world, 40.0D, 66.0D, 42.0D));
        Rig boat = Rig.vehicle("boat", new Location(world, 1.0D, 65.0D, 1.0D), 1.375D, 0.5625D);
        Rig driver = Rig.player("driver", new Location(world, 1.0D, 65.0D, 1.0D)).ride(boat);
        Rig horse = Rig.mob("horse", new Location(world, 1.0D, 65.0D, 3.0D), 1.4D, 1.6D).leashTo(driver);
        ConvoyGraph graph = ConvoyGraph.closure(driver.entity(), List.of(boat.entity(), driver.entity(), horse.entity()), 16);
        RecordingTeleporter teleporter = new RecordingTeleporter(true).failOn(horse.entity());
        List<Entity> settled = new ArrayList<Entity>();
        AtomicReference<String> failure = new AtomicReference<String>();

        new ConvoyLocalTraversal(teleporter).teleport(graph, destination, crossing(source, driver.entity()), new ConvoyLocalTraversal.Arrival() {
            @Override
            public void settle(Entity member, Traversive memberTraversive, boolean reloadExpected) {
                settled.add(member);
            }

            @Override
            public void completed() {
                settled.add(null);
            }

            @Override
            public void failed(String reason) {
                failure.set(reason);
            }
        });

        assertTrue(settled.isEmpty(), "nothing settles when the rig does not move as a whole");
        assertEquals("horse", failure.get());
        assertEquals(List.of(
            "dismount horse", "unleash horse", "dismount driver", "dismount boat",
            "teleport boat", "teleport driver", "teleport horse",
            "teleport driver", "teleport boat",
            "mount boat<-driver", "leash horse<-driver"), teleporter.log);
        assertEquals(new Location(world, 1.0D, 65.0D, 1.0D), boat.teleports().getLast());
        assertEquals(new Location(world, 1.0D, 65.0D, 1.0D), driver.teleports().getLast());
        assertEquals(0, horse.teleports().size(), "the failed member never moved, so it is not moved back");
    }

    private static Traversive crossing(LocalPortal source, Entity root) {
        return new Traversive(root, source.getFrame().view(true), source.getOrigin(), root.getLocation().toVector(),
            new Vector(-0.4D, 0.0D, 0.0D), new Vector(-1.0D, 0.0D, 0.0D), true, source.getId());
    }

    private static final class RecordingTeleporter implements ConvoyLocalTraversal.Teleporter {
        private final List<String> log = new ArrayList<String>();
        private final boolean regionAccepts;
        private Entity failing;

        private RecordingTeleporter(boolean regionAccepts) {
            this.regionAccepts = regionAccepts;
        }

        private RecordingTeleporter failOn(Entity entity) {
            this.failing = entity;
            return this;
        }

        @Override
        public void dismount(Entity entity) {
            log.add("dismount " + entity.getName());
            entity.eject();
            entity.leaveVehicle();
        }

        @Override
        public void unleash(Entity entity) {
            log.add("unleash " + entity.getName());
        }

        @Override
        public CompletionStage<Boolean> teleport(Entity entity, Location target) {
            log.add("teleport " + entity.getName());
            if (entity == failing) {
                return CompletableFuture.completedFuture(Boolean.FALSE);
            }
            entity.teleport(target);
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }

        @Override
        public void mount(Entity vehicle, Entity passenger) {
            log.add("mount " + vehicle.getName() + "<-" + passenger.getName());
            vehicle.addPassenger(passenger);
        }

        @Override
        public void leash(Entity leashed, Entity holder) {
            log.add("leash " + leashed.getName() + "<-" + holder.getName());
        }

        @Override
        public boolean runRegion(Location location, Runnable task) {
            if (!regionAccepts) {
                return false;
            }
            log.add("region");
            task.run();
            return true;
        }
    }
}
