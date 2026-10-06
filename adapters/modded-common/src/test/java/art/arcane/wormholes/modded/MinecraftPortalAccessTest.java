package art.arcane.wormholes.modded;

import art.arcane.wormholes.access.PortalRole;
import art.arcane.optics.math.Vec3;
import art.arcane.wormholes.portal.Portal;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.math.Face;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class MinecraftPortalAccessTest {
    @Test
    public void roleEditsPreservePreviousSnapshotsAndApplyAdmissionPolicy() {
        UUID owner = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        MinecraftPortal portal = portal(owner);
        Map<UUID, PortalRole> before = portal.getRoles();
        portal.setRole(trusted, PortalRole.USER);
        assertTrue(before.isEmpty());
        assertTrue(portal.allows(trusted, false, node -> false, false));
        assertFalse(portal.allows(outsider, false, node -> false, false));
        assertFalse(portal.canManage(trusted, false));
        portal.setRole(trusted, PortalRole.CO_OWNER);
        assertTrue(portal.canManage(trusted, false));
        portal.setRole(trusted, PortalRole.DENIED);
        assertFalse(portal.allows(trusted, false, node -> false, false));
        portal.setRole(trusted, null);
        assertTrue(portal.allows(outsider, false, node -> false, false));
        assertThrows(IllegalArgumentException.class, () -> portal.setRole(owner, PortalRole.DENIED));
    }

    @Test
    public void transferPersistsOwnerAndRemovesTheirPriorRoleOverride() {
        UUID owner = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        MinecraftPortal portal = portal(owner);
        portal.setRole(next, PortalRole.DENIED);
        portal.setOwner(next);
        MinecraftPortal loaded = MinecraftPortal.read(portal.write());
        assertEquals(next, loaded.getOwner());
        assertEquals(owner.toString(), loaded.setting("access.transferredFrom"));
        assertTrue(loaded.getRoles().isEmpty());
        assertTrue(loaded.canManage(next, false));
        assertFalse(loaded.canManage(owner, false));
        assertTrue(loaded.allows(next, false, node -> false, false));
    }

    private static MinecraftPortal portal(UUID owner) {
        ApertureCells geometry = new ApertureCells();
        geometry.setBlocks(List.of(new Vec3(1, 64, 1), new Vec3(1, 65, 1)));
        Portal.State state = new Portal.State(UUID.randomUUID(), new Vec3(1, 64, 1), "Access test", Frame.canonical(Face.N), true);
        return new MinecraftPortal(new MinecraftPortal.Definition(state, geometry, "minecraft:overworld",
            Map.of("owner", owner.toString(), "type", "PORTAL")));
    }
}
