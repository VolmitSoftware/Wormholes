package art.arcane.wormholes.api.traversal;

import art.arcane.wormholes.portal.PortalType;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WormholesPortalCreateEventTest {
    @Test
    void theEventCarriesTheOwnerTheFrameCellsAndTheType() {
        UUID ownerId = UUID.randomUUID();
        Set<Block> cells = new LinkedHashSet<>(Set.of(block(), block()));
        WormholesPortalCreateEvent event = new WormholesPortalCreateEvent(ownerId, null, cells, PortalType.GATEWAY);

        assertEquals(ownerId, event.getOwnerId());
        assertSame(PortalType.GATEWAY, event.getType());
        assertEquals(cells, event.getCells());
        assertFalse(event.isCancelled());
        assertEquals("", event.getCancelReason());
    }

    @Test
    void theCellsAreASnapshotAndCannotBeEditedByAListener() {
        Set<Block> cells = new LinkedHashSet<>(Set.of(block()));
        WormholesPortalCreateEvent event = new WormholesPortalCreateEvent(UUID.randomUUID(), null, cells, PortalType.PORTAL);
        Block extra = block();

        assertThrows(UnsupportedOperationException.class, () -> event.getCells().add(extra));
        cells.add(extra);
        assertEquals(1, event.getCells().size());
    }

    @Test
    void aListenerCanVetoTheBuildWithAReason() {
        WormholesPortalCreateEvent event = new WormholesPortalCreateEvent(UUID.randomUUID(), null, Set.of(block()), PortalType.PORTAL);

        event.setCancelled(true);
        event.setCancelReason("this is a monument");

        assertTrue(event.isCancelled());
        assertEquals("this is a monument", event.getCancelReason());
    }

    @Test
    void theHandlerListIsSharedBetweenTheInstanceAndTheStaticAccessor() {
        WormholesPortalCreateEvent event = new WormholesPortalCreateEvent(UUID.randomUUID(), null, Set.of(), PortalType.PORTAL);

        assertSame(WormholesPortalCreateEvent.getHandlerList(), event.getHandlers());
    }

    private static Block block() {
        return (Block) Proxy.newProxyInstance(WormholesPortalCreateEventTest.class.getClassLoader(), new Class<?>[] {Block.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "CreateEventTestBlock";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
