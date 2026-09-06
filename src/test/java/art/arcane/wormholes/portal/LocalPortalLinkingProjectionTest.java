package art.arcane.wormholes.portal;

import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.Wormholes;

import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LocalPortalLinkingProjectionTest {
    private ProjectionManager previousManager;
    private RecordingProjectionManager projections;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        previousManager = Wormholes.projectionManager;
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

    private static final class RecordingProjectionManager extends ProjectionManager {
        private int invalidations;
        private ILocalPortal lastPortal;

        private RecordingProjectionManager() {
            super(null);
        }

        @Override
        public void removeProjector(ILocalPortal portal) {
            invalidations++;
            lastPortal = portal;
        }
    }
}
