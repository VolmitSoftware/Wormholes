package art.arcane.wormholes.nexus;

import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.nexus.ReciprocalLinks.PairResult;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReciprocalLinksTest {
    private World world;
    private RecordingLinker linker;
    private ReciprocalLinks links;

    @BeforeEach
    void setUp() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new NexusExtensionFactory(null)));
        world = NexusTestSupport.world("reciprocal");
        linker = new RecordingLinker();
        links = new ReciprocalLinks(linker);
    }

    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void pairingLinksBothWaysAndMarksBothEndsAsReturning() {
        LocalPortal first = NexusTestSupport.portal(world, "first");
        LocalPortal second = NexusTestSupport.portal(world, "second", 40.0D, 0.0D);

        assertEquals(PairResult.PAIRED, links.pair(first, second));

        assertEquals(List.of(first.getId() + ">" + second.getId(), second.getId() + ">" + first.getId()), linker.links);
        assertTrue(first.extension(NexusPortalExtension.class).reciprocal());
        assertTrue(second.extension(NexusPortalExtension.class).reciprocal());
    }

    @Test
    void pairingAPortalWithItselfIsRefusedWithoutTouchingAnyLink() {
        LocalPortal only = NexusTestSupport.portal(world, "only");

        assertEquals(PairResult.SAME_PORTAL, links.pair(only, only));
        assertTrue(linker.links.isEmpty());
        assertFalse(only.extension(NexusPortalExtension.class).reciprocal());
    }

    @Test
    void aRefusedForwardLinkLeavesTheReturnLinkUnmade() {
        LocalPortal first = NexusTestSupport.portal(world, "first");
        LocalPortal second = NexusTestSupport.portal(world, "second", 40.0D, 0.0D);
        linker.refuse = true;

        assertEquals(PairResult.REFUSED, links.pair(first, second));
        assertTrue(linker.links.isEmpty());
        assertFalse(first.extension(NexusPortalExtension.class).reciprocal());
        assertFalse(second.extension(NexusPortalExtension.class).reciprocal());
    }

    @Test
    void unpairingClearsBothFlagsAndDropsTheReturnLink() {
        LocalPortal first = NexusTestSupport.portal(world, "first");
        LocalPortal second = NexusTestSupport.portal(world, "second", 40.0D, 0.0D);
        links.pair(first, second);

        assertEquals(PairResult.UNPAIRED, links.unpair(first, second));

        assertEquals(List.of(second.getId()), linker.unlinked);
        assertFalse(first.extension(NexusPortalExtension.class).reciprocal());
        assertFalse(second.extension(NexusPortalExtension.class).reciprocal());
    }

    @Test
    void destroyingOneEndOfAPairUnlinksTheOtherEnd() {
        LocalPortal first = NexusTestSupport.portal(world, "first");
        LocalPortal second = NexusTestSupport.portal(world, "second", 40.0D, 0.0D);
        linker.portals.put(first.getId(), first);
        linker.portals.put(second.getId(), second);
        links.pair(first, second);
        linker.destinations.put(first.getId(), second.getId());
        linker.destinations.put(second.getId(), first.getId());

        links.onPortalDestroyed(first, first.extension(NexusPortalExtension.class));

        assertEquals(List.of(second.getId()), linker.unlinked);
        assertFalse(second.extension(NexusPortalExtension.class).reciprocal());
    }

    @Test
    void destroyingAPortalThatIsNotPairedLeavesEveryOtherPortalAlone() {
        LocalPortal first = NexusTestSupport.portal(world, "first");
        LocalPortal second = NexusTestSupport.portal(world, "second", 40.0D, 0.0D);
        linker.portals.put(second.getId(), second);
        linker.destinations.put(first.getId(), second.getId());

        links.onPortalDestroyed(first, first.extension(NexusPortalExtension.class));

        assertTrue(linker.unlinked.isEmpty());
    }

    private static final class RecordingLinker implements ReciprocalLinks.Linker {
        private final Map<UUID, LocalPortal> portals = new LinkedHashMap<>();
        private final Map<UUID, UUID> destinations = new LinkedHashMap<>();
        private final List<String> links = new ArrayList<>();
        private final List<UUID> unlinked = new ArrayList<>();
        private boolean refuse;

        @Override
        public LocalPortal portal(UUID portalId) {
            return portals.get(portalId);
        }

        @Override
        public UUID destinationOf(LocalPortal portal) {
            return destinations.get(portal.getId());
        }

        @Override
        public boolean link(LocalPortal from, LocalPortal to) {
            if (refuse) {
                return false;
            }
            links.add(from.getId() + ">" + to.getId());
            return true;
        }

        @Override
        public void unlink(LocalPortal portal) {
            unlinked.add(portal.getId());
        }
    }
}
