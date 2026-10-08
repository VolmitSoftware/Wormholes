package art.arcane.wormholes.portal;

import art.arcane.optics.plate.PlateWorkers;
import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.BukkitOpticsScheduler;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class LocalPortalLinkingProjectionTest {
    private ProjectionManager previousManager;
    private PortalManager previousPortals;
    private RecordingProjectionManager projections;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        previousManager = Wormholes.projectionManager;
        previousPortals = Wormholes.portalManager;
        Class<?> allocatorType = Class.forName("sun.misc.Unsafe");
        Field singleton = allocatorType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object allocator = singleton.get(null);
        projections = (RecordingProjectionManager) allocatorType.getMethod("allocateInstance", Class.class)
            .invoke(allocator, RecordingProjectionManager.class);
        Wormholes.projectionManager = projections;
    }

    @AfterEach
    void tearDown() {
        Wormholes.projectionManager = previousManager;
        Wormholes.portalManager = previousPortals;
    }

    @Test
    void changingLocalDestinationRetiresExistingProjectionEvenWhenTheWorldIsUnchanged() {
        World world = LocalPortalTestSupport.world("relink");
        LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
        LocalPortal first = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
        LocalPortal second = LocalPortalTestSupport.portal(world, PortalType.PORTAL);

        assertTrue(source.setDestination(first));
        assertEquals(1, projections.invalidations);
        assertSame(source, projections.lastPortal);
        assertTrue(source.setDestination(second));
        assertEquals(2, projections.invalidations);
        assertSame(source, projections.lastPortal);
        assertEquals(second.getId(), ((LocalTunnel) source.getTunnel()).getDestinationId());
    }

    @Test
    void changingRemoteDestinationAndUnlinkingRetireExistingProjection() {
        LocalPortal source = LocalPortalTestSupport.portal(LocalPortalTestSupport.world("remote-relink"), PortalType.GATEWAY);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(source.linkRemote("alpha", first));
        assertEquals(1, projections.invalidations);
        assertTrue(source.linkRemote("beta", second));
        assertEquals(2, projections.invalidations);
        assertEquals(second, ((UniversalTunnel) source.getTunnel()).getDestinationPortalId());
        source.unlink();
        assertEquals(3, projections.invalidations);
        source.unlink();
        assertEquals(3, projections.invalidations);
    }

    @Test
    void rejectedDestinationChangeKeepsTheExistingProjection() {
        World world = LocalPortalTestSupport.world("relink-rejection");
        LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
        LocalPortal first = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
        LocalPortal rtp = LocalPortalTestSupport.portal(world, PortalType.RTP);
        assertTrue(source.setDestination(first));
        int beforeRejected = projections.invalidations;

        assertFalse(source.setDestination(rtp));

        assertEquals(beforeRejected, projections.invalidations);
        assertEquals(first.getId(), ((LocalTunnel) source.getTunnel()).getDestinationId());
    }

    @Test
    void addingReturnLinkPreservesExistingCrossWorldDestination() {
        LocalPortal source = LocalPortalTestSupport.portal(LocalPortalTestSupport.world("cross-world-source"), PortalType.PORTAL);
        LocalPortal destination = LocalPortalTestSupport.portal(LocalPortalTestSupport.world("cross-world-destination"), PortalType.PORTAL);
        PortalManager portals = mock(PortalManager.class);
        when(portals.getLocalPortal(source.getId())).thenReturn(source);
        when(portals.getLocalPortal(destination.getId())).thenReturn(destination);
        Wormholes.portalManager = portals;

        assertTrue(destination.setDestination(source));
        assertTrue(source.setDestination(destination));
        assertTrue(destination.setDestination(source));

        assertEquals(destination.getId(), source.getTunnel().getDestinationId());
        assertEquals(source.getId(), destination.getTunnel().getDestinationId());
    }

    private static final class RecordingProjectionManager extends ProjectionManager {
        private int invalidations;
        private ILocalPortal lastPortal;

        private RecordingProjectionManager() {
            super(null, new BukkitOpticsScheduler(mock(Plugin.class), new PlateWorkers("Linking-Test-", 1)));
        }

        @Override
        public void removeProjector(ILocalPortal portal) {
            invalidations++;
            lastPortal = portal;
        }
    }
}
