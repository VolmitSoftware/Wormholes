package art.arcane.wormholes.nexus;

import art.arcane.wormholes.access.AccessExtensionFactory;
import art.arcane.wormholes.access.AccessPortalExtension;
import art.arcane.wormholes.access.AccessTestPortals;
import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalPermissionMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dialing case from the headline review: a gesture repoints a portal for everybody and enumerates a
 * network, so it must run the portal's admission and honour the network's visibility.
 */
final class DialAdmissionTest {
    private World world;
    private LocalPortal portal;

    @BeforeEach
    void install() {
        WormholesHooks.install(new WormholesRegistrar()
            .portalExtension(new NexusExtensionFactory(null))
            .portalExtension(new AccessExtensionFactory()));
        world = NexusTestSupport.world("dial-admission");
        portal = NexusTestSupport.portal(world, "hub");
    }

    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void aDeniedPlayerCannotDialAPublicNetwork() {
        Player denied = AccessTestPortals.player("Denied", false, Set.of());
        portal.extension(AccessPortalExtension.class).setRole(denied.getUniqueId(), PortalRole.DENIED);

        assertFalse(DialAdmission.allows(portal, network(Visibility.PUBLIC, UUID.randomUUID()), denied));
    }

    @Test
    void aPlayerOffTheWhitelistCannotDial() {
        Player stranger = AccessTestPortals.player("Stranger", false, Set.of());
        portal.extension(AccessPortalExtension.class).setRole(UUID.randomUUID(), PortalRole.USER);

        assertFalse(DialAdmission.allows(portal, network(Visibility.PUBLIC, UUID.randomUUID()), stranger));
    }

    @Test
    void theStablePermissionNodeIsHonouredByTheGesture() {
        Player stranger = AccessTestPortals.player("Stranger", false, Set.of());
        portal.setPermissionMode(PortalPermissionMode.WHITELIST);

        assertFalse(DialAdmission.allows(portal, network(Visibility.PUBLIC, UUID.randomUUID()), stranger));
    }

    @Test
    void aNonPublicNetworkIsOnlyDialableByItsRoster() {
        Player stranger = AccessTestPortals.player("Stranger", false, Set.of());
        Player member = AccessTestPortals.player("Member", false, Set.of());
        Player administrator = AccessTestPortals.player("Admin", true, Set.of());
        PortalNetwork hidden = network(Visibility.HIDDEN, UUID.randomUUID())
            .withRole(member.getUniqueId(), NetworkRole.MEMBER);

        assertFalse(DialAdmission.allows(portal, hidden, stranger));
        assertTrue(DialAdmission.allows(portal, hidden, member));
        assertTrue(DialAdmission.allows(portal, hidden, administrator));
    }

    @Test
    void anOrdinaryPlayerStillDialsAPublicNetwork() {
        Player traveler = AccessTestPortals.player("Traveler", false, Set.of());

        assertTrue(DialAdmission.allows(portal, network(Visibility.PUBLIC, UUID.randomUUID()), traveler));
    }

    @Test
    void aPortalWithNoNetworkIsNeverDialable() {
        Player traveler = AccessTestPortals.player("Traveler", false, Set.of());

        assertFalse(DialAdmission.allows(portal, null, traveler));
    }

    private PortalNetwork network(Visibility visibility, UUID ownerId) {
        return PortalNetwork.create(UUID.randomUUID(), "mesh", ownerId)
            .withVisibility(visibility)
            .withMember(portal.getId(), new NetworkMember(portal.getId(), "AAAA", "", 0L, null));
    }
}
