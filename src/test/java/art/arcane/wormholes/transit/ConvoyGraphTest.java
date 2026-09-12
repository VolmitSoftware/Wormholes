package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.transit.TransitTestSupport.Rig;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

final class ConvoyGraphTest {
    @Test
    void closureFollowsVehiclesPassengersAndLeashesInDependencyOrder() {
        World world = TransitTestSupport.world("closure");
        Rig boat = Rig.vehicle("boat", at(world, 2.0D), 1.375D, 0.5625D);
        Rig driver = Rig.player("driver", at(world, 2.0D)).ride(boat);
        Rig passenger = Rig.player("passenger", at(world, 2.2D)).ride(boat);
        Rig horse = Rig.mob("horse", at(world, 5.0D), 1.4D, 1.6D).leashTo(driver);
        Rig cow = Rig.mob("cow", at(world, 6.0D), 0.9D, 1.4D);
        List<Entity> candidates = List.of(cow.entity(), horse.entity(), passenger.entity(), boat.entity(), driver.entity());

        ConvoyGraph graph = ConvoyGraph.closure(passenger.entity(), candidates, 16);

        assertTrue(graph.isRig());
        assertFalse(graph.overflow());
        assertEquals(4, graph.size());
        assertEquals(2, graph.playerCount());
        assertEquals(List.of(boat.entity(), passenger.entity(), driver.entity(), horse.entity()), entities(graph));
        assertSame(passenger.entity(), graph.root(), "the first player in dependency order carries the rig");
        assertTrue(graph.contains(horse.id()));
        assertFalse(graph.contains(cow.id()));
        ConvoyGraph.Member horseMember = graph.members().get(3);
        assertSame(driver.entity(), horseMember.leashHolder());
        assertSame(boat.entity(), graph.members().get(1).vehicle());
    }

    @Test
    void aLoneWalkerIsNotARig() {
        World world = TransitTestSupport.world("lone");
        Rig walker = Rig.player("walker", at(world, 1.0D));
        Rig cow = Rig.mob("cow", at(world, 3.0D), 0.9D, 1.4D);
        ConvoyGraph graph = ConvoyGraph.closure(walker.entity(), List.of(cow.entity(), walker.entity()), 16);
        assertFalse(graph.isRig());
        assertEquals(1, graph.size());
        assertSame(walker.entity(), graph.root());
    }

    @Test
    void leashHoldersOutsideTheCandidatesAreStillPulledIn() {
        World world = TransitTestSupport.world("holder");
        Rig holder = Rig.player("holder", at(world, 12.0D));
        Rig horse = Rig.mob("horse", at(world, 1.0D), 1.4D, 1.6D).leashTo(holder);
        ConvoyGraph graph = ConvoyGraph.closure(horse.entity(), List.of(horse.entity()), 16);
        assertEquals(List.of(holder.entity(), horse.entity()), entities(graph));
        assertSame(holder.entity(), graph.root());
    }

    @Test
    void theCapStopsTheWalkAndFlagsOverflow() {
        World world = TransitTestSupport.world("cap");
        Rig boat = Rig.vehicle("boat", at(world, 2.0D), 1.375D, 0.5625D);
        Rig driver = Rig.player("driver", at(world, 2.0D)).ride(boat);
        Rig passenger = Rig.player("passenger", at(world, 2.2D)).ride(boat);
        Rig horse = Rig.mob("horse", at(world, 5.0D), 1.4D, 1.6D).leashTo(driver);
        List<Entity> candidates = List.of(boat.entity(), driver.entity(), passenger.entity(), horse.entity());

        ConvoyGraph graph = ConvoyGraph.closure(driver.entity(), candidates, 3);

        assertTrue(graph.overflow());
        assertTrue(graph.size() > 3);
        assertFalse(ConvoyGraph.closure(driver.entity(), candidates, 4).overflow());
    }

    @Test
    void fitComparesTheRigsUnionBoxWithTheApertureAxes() {
        World world = TransitTestSupport.world("fit");
        LocalPortal portal = TransitTestSupport.portal(world);
        portal.setFrame(PortalFrame.canonical(Direction.W));
        AxisAlignedBB area = portal.getStructure().getArea();
        assertTrue(area.sizeX() < 1.0D, "test portal is a one-block-thick wall on the x axis");
        assertTrue(area.sizeZ() > 2.0D && area.sizeZ() < 3.0D, "three blocks wide along z");
        assertEquals(Direction.N, portal.getFrame().getRight(), "aperture width runs along z");

        Rig boat = Rig.vehicle("boat", at(world, 1.0D), 1.375D, 0.5625D);
        Rig driver = Rig.player("driver", at(world, 1.0D)).ride(boat);
        ConvoyGraph narrow = ConvoyGraph.closure(driver.entity(), List.of(boat.entity(), driver.entity()), 16);
        assertTrue(narrow.fits(portal.getStructure(), portal.getFrame()));

        Rig left = Rig.mob("left", at(world, 0.0D), 1.4D, 1.6D);
        Rig right = Rig.mob("right", at(world, 5.0D), 1.4D, 1.6D).leashTo(left);
        ConvoyGraph wide = ConvoyGraph.closure(left.entity(), List.of(left.entity(), right.entity()), 16);
        assertFalse(wide.fits(portal.getStructure(), portal.getFrame()));

        Rig tall = Rig.mob("tall", at(world, 1.0D), 0.9D, 6.0D);
        Rig rider = Rig.player("rider", at(world, 1.0D)).ride(tall);
        ConvoyGraph giant = ConvoyGraph.closure(rider.entity(), List.of(tall.entity(), rider.entity()), 16);
        assertFalse(giant.fits(portal.getStructure(), portal.getFrame()));
    }

    @Test
    void allInsidePlaneRequiresEveryMemberInsideTheCaptureZoneOfTheSameWorld() {
        World world = TransitTestSupport.world("inside");
        World elsewhere = TransitTestSupport.world("elsewhere");
        AxisAlignedBB zone = new AxisAlignedBB(-8.0D, 8.0D, 56.0D, 74.0D, -8.0D, 10.0D);
        Rig boat = Rig.vehicle("boat", at(world, 2.0D), 1.375D, 0.5625D);
        Rig driver = Rig.player("driver", at(world, 2.0D)).ride(boat);
        Rig horse = Rig.mob("horse", at(world, 9.0D), 1.4D, 1.6D).leashTo(driver);
        ConvoyGraph graph = ConvoyGraph.closure(driver.entity(), List.of(boat.entity(), driver.entity(), horse.entity()), 16);
        assertTrue(graph.allInsidePlane(world, zone));

        Rig straggler = Rig.mob("straggler", at(world, 30.0D), 1.4D, 1.6D).leashTo(driver);
        ConvoyGraph lagging = ConvoyGraph.closure(driver.entity(), List.of(boat.entity(), driver.entity(), straggler.entity()), 16);
        assertFalse(lagging.allInsidePlane(world, zone));

        Rig stray = Rig.mob("stray", new Location(elsewhere, 1.0D, 65.0D, 1.0D), 1.4D, 1.6D).leashTo(driver);
        ConvoyGraph crossWorld = ConvoyGraph.closure(driver.entity(), List.of(boat.entity(), driver.entity(), stray.entity()), 16);
        assertFalse(crossWorld.allInsidePlane(world, zone));
    }

    private static Location at(World world, double z) {
        return new Location(world, 1.0D, 65.0D, z);
    }

    private static List<Entity> entities(ConvoyGraph graph) {
        return graph.members().stream().map(ConvoyGraph.Member::entity).toList();
    }
}
