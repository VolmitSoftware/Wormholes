package art.arcane.wormholes.access;

import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PermissionKeysTest {
    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void sanitizeMatchesTheLegacyNameDerivedNodeRules() {
        assertEquals("front_gate", PermissionKeys.sanitize("Front Gate"));
        assertEquals("front_gate", PermissionKeys.sanitize("Front   Gate"));
        assertEquals("mine-1.a_b", PermissionKeys.sanitize("Mine-1.A_B"));
        assertEquals("unnamed", PermissionKeys.sanitize(null));
        assertEquals("unnamed", PermissionKeys.sanitize("   "));
        assertEquals("unnamed", PermissionKeys.sanitize("!!!"));
        assertEquals("hub", PermissionKeys.sanitize("  hub  "));
    }

    @Test
    void validKeysAreLowercaseAndAtMostSixtyFourCharacters() {
        assertTrue(PermissionKeys.isValid("hub"));
        assertTrue(PermissionKeys.isValid("mine-1.a_b"));
        assertTrue(PermissionKeys.isValid("a".repeat(64)));
        assertFalse(PermissionKeys.isValid("a".repeat(65)));
        assertFalse(PermissionKeys.isValid("Hub"));
        assertFalse(PermissionKeys.isValid("front gate"));
        assertFalse(PermissionKeys.isValid(""));
        assertFalse(PermissionKeys.isValid(null));
    }

    @Test
    void nodeIsThePortalPermissionPrefixPlusTheKey() {
        assertEquals("wormholes.portal.hub", PermissionKeys.node("hub"));
    }

    @Test
    void aKeyIsTakenOnlyWhenAnotherPortalAlreadyUsesIt() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new AccessExtensionFactory()));
        World world = AccessTestPortals.world("permission-keys");
        LocalPortal owner = AccessTestPortals.portal(world);
        LocalPortal other = AccessTestPortals.portal(world);
        owner.setName("Front Gate");
        assertEquals("front_gate", owner.extension(AccessPortalExtension.class).permissionKey());
        List<ILocalPortal> portals = List.of(owner, other);

        assertTrue(PermissionKeys.isTaken("front_gate", other.getId(), portals));
        assertFalse(PermissionKeys.isTaken("front_gate", owner.getId(), portals));
        assertFalse(PermissionKeys.isTaken("back_gate", other.getId(), portals));
    }

    @Test
    void anUnregisteredExtensionMakesEveryKeyFree() {
        World world = AccessTestPortals.world("permission-keys-off");
        LocalPortal portal = AccessTestPortals.portal(world);

        assertFalse(PermissionKeys.isTaken("front_gate", UUID.randomUUID(), List.of(portal)));
    }
}
