package art.arcane.wormholes.access;

import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PortalLimitsTest {
    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void theHighestLimitNodeWins() {
        assertEquals(12, PortalLimits.highestLimitNode(List.of(
            "wormholes.limit.3", "wormholes.limit.12", "wormholes.limit.7", "wormholes.admin")));
        assertEquals(-1, PortalLimits.highestLimitNode(List.of("wormholes.admin", "essentials.home")));
    }

    @Test
    void malformedLimitNodesAreIgnored() {
        assertEquals(4, PortalLimits.highestLimitNode(List.of(
            "wormholes.limit.", "wormholes.limit.many", "wormholes.limit.-2", "wormholes.limit.4")));
        assertEquals(-1, PortalLimits.highestLimitNode(List.of("wormholes.limit.nine")));
    }

    @Test
    void aLimitNodeOverridesTheConfiguredDefault() {
        Player limited = AccessTestPortals.player("Limited", false, Set.of("wormholes.limit.2"));

        assertEquals(2, PortalLimits.maximum(limited, 5));
        assertEquals(2, PortalLimits.maximum(limited, 0));
    }

    @Test
    void withoutALimitNodeTheConfiguredDefaultApplies() {
        Player plain = AccessTestPortals.player("Plain", false, Set.of());

        assertEquals(5, PortalLimits.maximum(plain, 5));
        assertEquals(0, PortalLimits.maximum(plain, 0));
        assertEquals(0, PortalLimits.maximum(plain, -3));
    }

    @Test
    void administratorsAreUnlimited() {
        Player operator = AccessTestPortals.player("Admin", true, Set.of());
        Player staff = AccessTestPortals.player("Staff", false, Set.of("wormholes.admin", "wormholes.limit.1"));

        assertEquals(0, PortalLimits.maximum(operator, 3));
        assertEquals(0, PortalLimits.maximum(staff, 3));
    }

    @Test
    void ownedCountsOnlyThePlayersOwnPortals() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new AccessExtensionFactory()));
        World world = AccessTestPortals.world("limits");
        UUID ownerId = UUID.randomUUID();
        LocalPortal mine = AccessTestPortals.portal(world);
        LocalPortal alsoMine = AccessTestPortals.portal(world);
        LocalPortal theirs = AccessTestPortals.portal(world);
        LocalPortal systemOwned = AccessTestPortals.portal(world);
        mine.setOwner(ownerId);
        alsoMine.setOwner(ownerId);
        theirs.setOwner(UUID.randomUUID());
        List<ILocalPortal> portals = List.of(mine, alsoMine, theirs, systemOwned);

        assertEquals(2, PortalLimits.owned(ownerId, portals));
        assertEquals(0, PortalLimits.owned(UUID.randomUUID(), portals));
    }

    @Test
    void remainingIsUnlimitedForAdministratorsAndCountsDownForEveryoneElse() {
        World world = AccessTestPortals.world("limits-remaining");
        UUID ownerId = UUID.randomUUID();
        LocalPortal mine = AccessTestPortals.portal(world);
        mine.setOwner(ownerId);
        List<ILocalPortal> portals = List.of(mine);

        assertEquals(-1, PortalLimits.remaining(0, ownerId, portals));
        assertEquals(2, PortalLimits.remaining(3, ownerId, portals));
        assertEquals(0, PortalLimits.remaining(1, ownerId, portals));
    }
}
