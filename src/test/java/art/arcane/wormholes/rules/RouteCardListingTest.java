package art.arcane.wormholes.rules;

import art.arcane.wormholes.access.AccessExtensionFactory;
import art.arcane.wormholes.access.AccessPortalExtension;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalType;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The route-card case from the headline review: access.listed was honoured only for a remote
 * destination, so an unlisted local destination still had its name printed to anyone in the source.
 */
final class RouteCardListingTest {
    private World world;

    @BeforeEach
    void install() {
        WormholesHooks.install(new WormholesRegistrar()
            .portalExtension(new RulesExtensionFactory())
            .portalExtension(new AccessExtensionFactory()));
        world = RulesTestSupport.world("route-card-listing");
    }

    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void anUnlistedLocalDestinationIsNotNamedOnTheCard() {
        LocalPortal source = RulesTestSupport.portal(world, PortalType.PORTAL);
        LocalPortal destination = RulesTestSupport.portal(world, PortalType.PORTAL);
        source.setDestination(destination);

        assertTrue(RouteCardService.listed(source));

        destination.extension(AccessPortalExtension.class).setListed(false);

        assertFalse(RouteCardService.listed(source));
    }
}
