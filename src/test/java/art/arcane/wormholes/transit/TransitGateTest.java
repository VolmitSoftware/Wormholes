package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalGate;
import art.arcane.wormholes.hook.TraversalPhase;
import art.arcane.wormholes.hook.TraversalVerdict;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.LocalTunnel;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.transit.TransitTestSupport.Rig;
import art.arcane.wormholes.util.Direction;

final class TransitGateTest {
    private static final double EPSILON = 1e-9D;

    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void gateRunsInTheTransitSlot() {
        assertEquals(TraversalGate.ORDER_TRANSIT, new TransitGate().order());
    }

    @Test
    void defaultsAllowFromEitherSideAndPortalsWithoutTheExtensionAllow() {
        TransitGate gate = new TransitGate();
        LocalPortal bare = TransitTestSupport.portal(TransitTestSupport.world("bare"));
        assertNull(bare.extension(TransitPortalExtension.class));
        assertSame(TraversalVerdict.ALLOW, gate.evaluate(depart(bare, entity(), true)));

        WormholesHooks.install(new WormholesRegistrar().portalExtension(new TransitExtensionFactory()));
        LocalPortal portal = TransitTestSupport.portal(TransitTestSupport.world("defaults"));
        assertSame(TraversalVerdict.ALLOW, gate.evaluate(depart(portal, entity(), true)));
        assertSame(TraversalVerdict.ALLOW, gate.evaluate(depart(portal, entity(), false)));
    }

    @Test
    void membraneDeniesBackSideEntryWithABounceAndAllowsTheFront() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new TransitExtensionFactory()));
        LocalPortal portal = TransitTestSupport.portal(TransitTestSupport.world("membrane"));
        portal.setName("Gate of Dawn");
        portal.extension(TransitPortalExtension.class).setMembrane(true);
        TransitGate gate = new TransitGate();

        TraversalVerdict.Deny deny = assertInstanceOf(TraversalVerdict.Deny.class, gate.evaluate(depart(portal, entity(), false)));
        assertSame(TransitMessages.DENIED_MEMBRANE, deny.reason());
        assertTrue(deny.bounce());
        assertEquals("Gate of Dawn", deny.args().require("portal").value());
        assertSame(TraversalVerdict.ALLOW, gate.evaluate(depart(portal, entity(), true)));
    }

    @Test
    void bounceDeniesEverySideAndArrivalsAreNeverGated() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new TransitExtensionFactory()));
        LocalPortal portal = TransitTestSupport.portal(TransitTestSupport.world("bounce"));
        portal.extension(TransitPortalExtension.class).setBounce(true);
        portal.extension(TransitPortalExtension.class).setMembrane(true);
        TransitGate gate = new TransitGate();

        TraversalVerdict.Deny front = assertInstanceOf(TraversalVerdict.Deny.class, gate.evaluate(depart(portal, entity(), true)));
        TraversalVerdict.Deny back = assertInstanceOf(TraversalVerdict.Deny.class, gate.evaluate(depart(portal, entity(), false)));
        assertSame(TransitMessages.BOUNCED, front.reason());
        assertSame(TransitMessages.BOUNCED, back.reason());
        assertTrue(front.bounce());
        assertSame(TraversalVerdict.ALLOW, gate.evaluate(new TraversalAttempt(TraversalPhase.ARRIVE, portal, entity(), null, null, 1L)));
    }

    @Test
    void observerReflectsTheEntryVelocityAcrossTheFrameOnABounce() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new TransitExtensionFactory()));
        LocalPortal portal = TransitTestSupport.portal(TransitTestSupport.world("reflect"));
        portal.extension(TransitPortalExtension.class).setBounce(true);
        AtomicReference<Vector> velocity = new AtomicReference<Vector>();
        Entity traveler = entity(velocity);
        Traversive crossing = new Traversive(traveler, portal.getFrame().view(true), portal.getOrigin(),
            new Vector(0.5D, 65.0D, 1.0D), new Vector(-0.4D, 0.1D, 0.3D), new Vector(-1.0D, 0.0D, 0.0D), true);
        TraversalAttempt attempt = new TraversalAttempt(TraversalPhase.DEPART, portal, traveler, null, crossing, 1L);
        TraversalVerdict.Deny deny = assertInstanceOf(TraversalVerdict.Deny.class, new TransitGate().evaluate(attempt));

        new TransitObserver().onRejected(attempt, deny);

        Vector normal = portal.getFrame().getNormal().toVector();
        Vector expected = new Vector(-0.4D, 0.1D, 0.3D);
        expected.subtract(normal.clone().multiply(2.0D * expected.dot(normal)));
        assertVector(expected, velocity.get());
    }

    @Test
    void observerLeavesOtherDenialsAlone() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new TransitExtensionFactory()));
        LocalPortal portal = TransitTestSupport.portal(TransitTestSupport.world("membrane-observer"));
        portal.extension(TransitPortalExtension.class).setMembrane(true);
        AtomicReference<Vector> velocity = new AtomicReference<Vector>();
        Entity traveler = entity(velocity);
        TraversalAttempt attempt = depart(portal, traveler, false);
        TraversalVerdict.Deny deny = assertInstanceOf(TraversalVerdict.Deny.class, new TransitGate().evaluate(attempt));

        new TransitObserver().onRejected(attempt, deny);

        assertNull(velocity.get());
    }

    @Test
    void rigMembersDeferUntilTheRootCrossesAndTheRootCommitsTheRig() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new TransitExtensionFactory()));
        World world = TransitTestSupport.world("convoy-gate");
        LocalPortal portal = TransitTestSupport.portal(world);
        portal.setFrame(PortalFrame.canonical(Direction.W));
        LocalPortal destination = TransitTestSupport.portal(world);
        Rig boat = Rig.vehicle("boat", new Location(world, 1.0D, 65.0D, 1.0D), 1.375D, 0.5625D);
        Rig driver = Rig.player("driver", new Location(world, 1.0D, 65.0D, 1.0D)).ride(boat);
        List<Entity> nearby = List.of(boat.entity(), driver.entity());
        TransitGate gate = new TransitGate(ignored -> nearby);
        long now = 5_000L;

        TraversalVerdict.Defer deferred = assertInstanceOf(TraversalVerdict.Defer.class,
            gate.evaluate(departVia(portal, boat.entity(), destination, now)));
        assertSame(TransitMessages.CONVOY_WAITING, deferred.reason());
        assertNull(portal.extension(TransitPortalExtension.class).takeCommittedConvoy(driver.id(), now));

        assertSame(TraversalVerdict.ALLOW, gate.evaluate(departVia(portal, driver.entity(), destination, now)));
        ConvoyGraph committed = portal.extension(TransitPortalExtension.class).takeCommittedConvoy(driver.id(), now);
        assertEquals(2, committed.size());
        assertSame(driver.entity(), committed.root());
        assertNull(portal.extension(TransitPortalExtension.class).takeCommittedConvoy(driver.id(), now), "a committed rig is taken once");
        assertNull(portal.extension(TransitPortalExtension.class).takeCommittedConvoy(boat.id(), now), "only the root may take it");
    }

    @Test
    void rigsAreRefusedWhenTooLargeTooWideOrWhenAMemberFailsAGate() {
        TransitConfig config = new TransitConfig();
        config.convoyMaxEntities = 2;
        TransitSubsystem.apply(config);
        try {
            World world = TransitTestSupport.world("convoy-refusals");
            LocalPortal destination = TransitTestSupport.portal(world);
            Rig boat = Rig.vehicle("boat", new Location(world, 1.0D, 65.0D, 1.0D), 1.375D, 0.5625D);
            Rig driver = Rig.player("driver", new Location(world, 1.0D, 65.0D, 1.0D)).ride(boat);
            Rig horse = Rig.mob("horse", new Location(world, 1.0D, 65.0D, 2.0D), 1.4D, 1.6D).leashTo(driver);
            List<Entity> nearby = List.of(boat.entity(), driver.entity(), horse.entity());

            WormholesHooks.install(new WormholesRegistrar().portalExtension(new TransitExtensionFactory()));
            LocalPortal portal = TransitTestSupport.portal(world);
            portal.setFrame(PortalFrame.canonical(Direction.W));
            TraversalVerdict.Deny tooLarge = assertInstanceOf(TraversalVerdict.Deny.class,
                new TransitGate(ignored -> nearby).evaluate(departVia(portal, driver.entity(), destination, 1L)));
            assertSame(TransitMessages.DENIED_CONVOY_SIZE, tooLarge.reason());
            assertEquals("3", tooLarge.args().require("count").value());
            assertEquals("2", tooLarge.args().require("value").value());

            config.convoyMaxEntities = 16;
            Rig wide = Rig.mob("wide", new Location(world, 1.0D, 65.0D, 9.0D), 1.4D, 1.6D).leashTo(driver);
            List<Entity> wideRig = List.of(boat.entity(), driver.entity(), wide.entity());
            TraversalVerdict.Deny tooWide = assertInstanceOf(TraversalVerdict.Deny.class,
                new TransitGate(ignored -> wideRig).evaluate(departVia(portal, driver.entity(), destination, 1_000L)));
            assertSame(TransitMessages.DENIED_CONVOY_FIT, tooWide.reason());

            TraversalGate refuseHorse = new TraversalGate() {
                @Override
                public int order() {
                    return ORDER_ACCESS;
                }

                @Override
                public TraversalVerdict evaluate(TraversalAttempt attempt) {
                    return attempt.traveler() == horse.entity()
                        ? TraversalVerdict.Deny.of(TransitMessages.DENIED_MEMBRANE) : TraversalVerdict.ALLOW;
                }
            };
            WormholesHooks.install(new WormholesRegistrar().portalExtension(new TransitExtensionFactory()).traversalGate(refuseHorse));
            LocalPortal guarded = TransitTestSupport.portal(world);
            guarded.setFrame(PortalFrame.canonical(Direction.W));
            TraversalVerdict.Deny refused = assertInstanceOf(TraversalVerdict.Deny.class,
                new TransitGate(ignored -> nearby).evaluate(departVia(guarded, driver.entity(), destination, 2_000L)));
            assertSame(TransitMessages.DENIED_CONVOY_MEMBER, refused.reason());
            assertNull(guarded.extension(TransitPortalExtension.class).takeCommittedConvoy(driver.id(), 2_000L));
        } finally {
            TransitSubsystem.apply(null);
        }
    }

    @Test
    void rigsIgnoreRandomTeleportPortalsAndCrossServerRigsNeedOnePlayerAnEnabledSwitchAndAResolvedPeer() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new TransitExtensionFactory()));
        World world = TransitTestSupport.world("convoy-kinds");
        LocalPortal portal = TransitTestSupport.portal(world);
        portal.setFrame(PortalFrame.canonical(Direction.W));
        Rig boat = Rig.vehicle("boat", new Location(world, 1.0D, 65.0D, 1.0D), 1.375D, 0.5625D);
        Rig driver = Rig.player("driver", new Location(world, 1.0D, 65.0D, 1.0D)).ride(boat);
        List<Entity> nearby = List.of(boat.entity(), driver.entity());
        TransitGate gate = new TransitGate(ignored -> nearby);

        assertSame(TraversalVerdict.ALLOW, gate.evaluate(depart(portal, driver.entity(), true)), "RTP portals refuse rigs in the traversal, not the gate");

        UniversalTunnel remote = new UniversalTunnel("beta", UUID.randomUUID());
        TraversalVerdict.Deny unresolved = assertInstanceOf(TraversalVerdict.Deny.class,
            gate.evaluate(new TraversalAttempt(TraversalPhase.DEPART, portal, driver.entity(), remote, crossing(portal, driver.entity()), 4L)));
        assertSame(TransitMessages.DENIED_CONVOY_MEMBER, unresolved.reason(), "a peer portal that cannot be resolved refuses every member");

        Rig passenger = Rig.player("passenger", new Location(world, 1.0D, 65.0D, 1.2D)).ride(boat);
        List<Entity> twoPlayers = List.of(boat.entity(), driver.entity(), passenger.entity());
        TraversalVerdict.Deny crowded = assertInstanceOf(TraversalVerdict.Deny.class,
            new TransitGate(ignored -> twoPlayers).evaluate(new TraversalAttempt(TraversalPhase.DEPART, portal, driver.entity(), remote, crossing(portal, driver.entity()), 1_000L)));
        assertSame(TransitMessages.DENIED_CONVOY_MEMBER, crowded.reason(), "cross-server rigs carry exactly one player");

        TransitConfig config = new TransitConfig();
        config.convoyCrossServerEnabled = false;
        TransitSubsystem.apply(config);
        try {
            TraversalVerdict.Deny disabled = assertInstanceOf(TraversalVerdict.Deny.class,
                gate.evaluate(new TraversalAttempt(TraversalPhase.DEPART, portal, driver.entity(), remote, crossing(portal, driver.entity()), 2_000L)));
            assertSame(TransitMessages.DENIED_CONVOY_MEMBER, disabled.reason());
        } finally {
            TransitSubsystem.apply(null);
        }
    }

    private static TraversalAttempt departVia(LocalPortal portal, Entity traveler, LocalPortal destination, long now) {
        return new TraversalAttempt(TraversalPhase.DEPART, portal, traveler, new LocalTunnel(destination), crossing(portal, traveler), now);
    }

    private static Traversive crossing(LocalPortal portal, Entity traveler) {
        return new Traversive(traveler, portal.getFrame().view(true), portal.getOrigin(),
            traveler.getLocation().toVector(), new Vector(-0.4D, 0.0D, 0.0D), new Vector(-1.0D, 0.0D, 0.0D), true, portal.getId());
    }

    private static TraversalAttempt depart(LocalPortal portal, Entity traveler, boolean frontSide) {
        Traversive crossing = new Traversive(traveler, portal.getFrame().view(frontSide), portal.getOrigin(),
            new Vector(0.5D, 65.0D, 1.0D), new Vector(-0.4D, 0.0D, 0.0D), new Vector(-1.0D, 0.0D, 0.0D), frontSide);
        return new TraversalAttempt(TraversalPhase.DEPART, portal, traveler, null, crossing, 1L);
    }

    private static Entity entity() {
        return entity(new AtomicReference<Vector>());
    }

    private static Entity entity(AtomicReference<Vector> velocity) {
        UUID id = UUID.randomUUID();
        return (Entity) Proxy.newProxyInstance(TransitGateTest.class.getClassLoader(), new Class<?>[] {Entity.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "traveler";
                case "setVelocity" -> {
                    velocity.set(((Vector) arguments[0]).clone());
                    yield null;
                }
                case "getPassengers" -> java.util.List.of();
                case "getVehicle" -> null;
                case "isValid" -> Boolean.TRUE;
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "entity-" + id;
                default -> TransitTestSupport.defaultValue(method.getReturnType());
            });
    }

    private static void assertVector(Vector expected, Vector actual) {
        assertEquals(expected.getX(), actual.getX(), EPSILON, "x");
        assertEquals(expected.getY(), actual.getY(), EPSILON, "y");
        assertEquals(expected.getZ(), actual.getZ(), EPSILON, "z");
    }
}
