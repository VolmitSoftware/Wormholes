package art.arcane.wormholes.menu.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.nexus.DialOutcome;
import art.arcane.wormholes.nexus.Dialer;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.nexus.NexusPortalExtension;
import art.arcane.wormholes.nexus.NexusSubsystem;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;

class BukkitDialMenuHostTest {
    private final UUID portalId = UUID.randomUUID();
    private LocalPortal portal;
    private Dialer dialer;
    private NexusSubsystem nexus;
    private MenuViewer viewer;
    private BukkitDialMenuHost host;

    @BeforeEach
    void setUp() {
        portal = mock(LocalPortal.class);
        when(portal.getId()).thenReturn(portalId);
        when(portal.extension(NexusPortalExtension.class)).thenReturn(mock(NexusPortalExtension.class));
        PortalManager portals = mock(PortalManager.class);
        when(portals.getLocalPortal(portalId)).thenReturn(portal);
        dialer = mock(Dialer.class);
        nexus = mock(NexusSubsystem.class);
        when(nexus.dialer()).thenReturn(dialer);
        when(nexus.registry()).thenReturn(mock(NetworkRegistry.class));
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        viewer = new BukkitMenuViewer(player);
        host = new BukkitDialMenuHost(portals, () -> nexus);
    }

    @Test
    void everyDialResultMapsToItsMenuOutcome() {
        UUID viewerId = viewer.id();
        for (Dialer.DialResult result : Dialer.DialResult.values()) {
            when(dialer.dial(eq(portal), eq("BETA"), eq(viewerId), anyLong())).thenReturn(result);

            assertEquals(result.name(), host.dial(viewer, portalId, "BETA").name());
        }
    }

    @Test
    void aMissingPortalOrDialerIsNotOnANetwork() {
        assertEquals(DialOutcome.NOT_ON_NETWORK, host.dial(viewer, UUID.randomUUID(), "BETA"));

        nexus = null;

        assertEquals(DialOutcome.NOT_ON_NETWORK, host.dial(viewer, portalId, "BETA"));
    }

    @Test
    void worldNamesComeFromThePortalStructure() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world_nether");
        PortalStructure structure = mock(PortalStructure.class);
        when(structure.getWorld()).thenReturn(world);
        when(portal.getStructure()).thenReturn(structure);

        assertEquals("world_nether", host.worldName(portalId));
        assertEquals("", host.worldName(UUID.randomUUID()));
    }

    @Test
    void aPortalWithoutANetworkCannotBeDialled() {
        assertFalse(host.dialAdmission(viewer, portalId));
        assertFalse(host.dialAdmission(viewer, UUID.randomUUID()));
    }
}
