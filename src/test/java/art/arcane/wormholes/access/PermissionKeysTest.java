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
    void aKeyIsTakenOnlyWhenAnotherPortalAlreadyUsesIt() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(AccessPortalExtension.class, AccessPortalExtension::new));
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
