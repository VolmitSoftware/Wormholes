package art.arcane.wormholes.access;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AccessPortalExtensionTest {
    private World world;

    @BeforeEach
    void registerExtension() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new AccessExtensionFactory()));
        world = AccessTestPortals.world("access-extension");
    }

    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void permissionKeyDefaultsFromTheNameOnceAndSurvivesARename() {
        LocalPortal portal = AccessTestPortals.portal(world);
        portal.setName("Front Gate");
        AccessPortalExtension access = portal.extension(AccessPortalExtension.class);

        assertEquals("front_gate", access.permissionKey());

        portal.setName("Somewhere Else");

        assertEquals("front_gate", access.permissionKey());
        assertEquals("wormholes.portal.front_gate", access.permissionNode());
    }

    @Test
    void aKeyAlreadyUsedByAnotherPortalIsRefused() {
        LocalPortal owner = AccessTestPortals.portal(world);
        LocalPortal other = AccessTestPortals.portal(world);
        owner.setName("Front Gate");
        owner.extension(AccessPortalExtension.class).permissionKey();
        List<ILocalPortal> portals = List.of(owner, other);
        AccessPortalExtension access = other.extension(AccessPortalExtension.class);

        assertEquals(AccessPortalExtension.KeyResult.TAKEN, access.setPermissionKey("front_gate", portals));
        assertEquals(AccessPortalExtension.KeyResult.INVALID, access.setPermissionKey("Front Gate", portals));
        assertEquals(AccessPortalExtension.KeyResult.SET, access.setPermissionKey("back_gate", portals));
        assertEquals("back_gate", access.permissionKey());
        assertEquals(AccessPortalExtension.KeyResult.UNCHANGED, access.setPermissionKey("back_gate", portals));
    }

    @Test
    void rolesGroupsAndListedRoundTripThroughPortalJson() {
        LocalPortal source = AccessTestPortals.portal(world);
        UUID coOwner = UUID.randomUUID();
        UUID denied = UUID.randomUUID();
        AccessPortalExtension access = source.extension(AccessPortalExtension.class);
        access.setPermissionKey("hub", List.of(source));
        access.setRole(coOwner, PortalRole.CO_OWNER);
        access.setRole(denied, PortalRole.DENIED);
        access.addGroup("group.staff");
        access.setListed(false);

        JSONObject encoded = source.toJSON();
        assertEquals("hub", encoded.getString("access.permissionKey"));
        assertFalse(encoded.getBoolean("access.listed"));

        LocalPortal target = AccessTestPortals.portal(world);
        AccessPortalExtension loaded = target.extension(AccessPortalExtension.class);
        loaded.load(encoded);

        assertEquals("hub", loaded.permissionKey());
        assertEquals(PortalRole.CO_OWNER, loaded.role(coOwner));
        assertEquals(PortalRole.DENIED, loaded.role(denied));
        assertEquals(List.of("group.staff"), loaded.groups());
        assertFalse(loaded.listed());
        assertEquals(List.of(coOwner, denied), List.copyOf(loaded.roles().keySet()));
    }

    @Test
    void aTrustedRoleMakesThePortalWhitelistOnlyAndRemovalUndoesIt() {
        LocalPortal portal = AccessTestPortals.portal(world);
        UUID trusted = UUID.randomUUID();
        UUID denied = UUID.randomUUID();
        AccessPortalExtension access = portal.extension(AccessPortalExtension.class);

        assertFalse(access.whitelistOnly());

        access.setRole(denied, PortalRole.DENIED);
        assertFalse(access.whitelistOnly());

        access.setRole(trusted, PortalRole.USER);
        assertTrue(access.whitelistOnly());

        assertTrue(access.removeRole(trusted));
        assertFalse(access.whitelistOnly());
        assertNull(access.role(trusted));
        assertFalse(access.removeRole(trusted));
    }

    @Test
    void onlyTheKeyAndTheDirectoryFlagAreReplicatedToLinkedPortals() {
        LocalPortal source = AccessTestPortals.portal(world);
        AccessPortalExtension access = source.extension(AccessPortalExtension.class);
        access.setPermissionKey("hub", List.of(source));
        access.setRole(UUID.randomUUID(), PortalRole.USER);
        access.setListed(false);

        Map<String, String> settings = new LinkedHashMap<>();
        source.extensions().collectSync(settings);

        assertEquals(Map.of("access.permissionKey", "hub", "access.listed", "false"), settings);

        LocalPortal target = AccessTestPortals.portal(world);
        target.extensions().applySync(settings);
        AccessPortalExtension mirrored = target.extension(AccessPortalExtension.class);

        assertEquals("hub", mirrored.permissionKey());
        assertFalse(mirrored.listed());
        assertTrue(mirrored.roles().isEmpty());
    }

    @Test
    void transferRecordsThePreviousOwnerAndDropsTheNewOwnersRole() {
        LocalPortal portal = AccessTestPortals.portal(world);
        UUID previous = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        portal.setOwner(previous);
        AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
        access.setRole(next, PortalRole.DENIED);

        access.recordTransfer(previous, next);

        assertEquals(previous, access.transferredFrom());
        assertNull(access.role(next));

        JSONObject encoded = portal.toJSON();
        LocalPortal target = AccessTestPortals.portal(world);
        AccessPortalExtension reloaded = target.extension(AccessPortalExtension.class);
        reloaded.load(encoded);

        assertEquals(previous, reloaded.transferredFrom());
    }
}
