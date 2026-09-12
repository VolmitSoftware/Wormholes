package art.arcane.wormholes.nexus;

import art.arcane.wormholes.config.toml.NexusConfig;
import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalPhase;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.nexus.FrameIo.ComparatorOutput;
import art.arcane.wormholes.nexus.FrameIo.RedstoneAction;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The traversal-counter case from the headline review: the counter grew a boxed stamp per departure for
 * every portal on the server, whether or not anything read it, and was appended to without the lock its
 * reader takes.
 */
final class RedstoneIoCounterTest {
    private World world;
    private RedstoneIo io;

    @BeforeEach
    void install() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new NexusExtensionFactory(null)));
        world = NexusTestSupport.world("redstone-counter");
        NetworkRegistry registry = new NetworkRegistry(java.nio.file.Path.of("build", "tmp", "redstone-counter"));
        Dialer dialer = new Dialer(registry, (portal, member) -> true, NexusConfig::new);
        io = new RedstoneIo(new RedstoneIoIndex(), dialer, NexusConfig::new, List::of);
    }

    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void aPortalWithoutATraversalComparatorRecordsNothing() {
        LocalPortal quiet = NexusTestSupport.portal(world, "quiet");
        LocalPortal stateOnly = NexusTestSupport.portal(world, "state", 40.0D, 0.0D);
        stateOnly.extension(NexusPortalExtension.class)
            .setFrameIo(new FrameIo(1, 0, 0, RedstoneAction.NONE, ComparatorOutput.STATE));

        for (int departure = 0; departure < 50; departure++) {
            io.onDeparted(depart(quiet, 1_000L + departure));
            io.onDeparted(depart(stateOnly, 1_000L + departure));
        }

        assertEquals(0, io.traversals().inLastMinute(quiet.getId(), 2_000L));
        assertEquals(0, io.traversals().inLastMinute(stateOnly.getId(), 2_000L));
    }

    @Test
    void aWiredPortalsWindowIsBoundedAtTheComparatorCeiling() {
        LocalPortal wired = NexusTestSupport.portal(world, "wired");
        wired.extension(NexusPortalExtension.class)
            .setFrameIo(new FrameIo(1, 0, 0, RedstoneAction.NONE, ComparatorOutput.TRAVERSALS));

        for (int departure = 0; departure < 5_000; departure++) {
            io.onDeparted(depart(wired, 1_000L + departure));
        }

        int counted = io.traversals().inLastMinute(wired.getId(), 6_000L);
        assertEquals(16, counted);
        assertEquals(15, RedstoneIo.comparatorLevel(ComparatorOutput.TRAVERSALS, true, counted));
    }

    private TraversalAttempt depart(LocalPortal portal, long nowMillis) {
        return new TraversalAttempt(TraversalPhase.DEPART, portal, traveler(), null, null, nowMillis);
    }

    private static Entity traveler() {
        UUID id = UUID.randomUUID();
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[] {Entity.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "traveler";
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "toString" -> "RedstoneTestTraveler";
                default -> null;
            });
    }
}
