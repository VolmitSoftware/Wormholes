package art.arcane.wormholes;

import art.arcane.wormholes.access.AccessGuard;
import art.arcane.wormholes.access.AccessGuards;
import art.arcane.wormholes.access.PlacementKind;
import art.arcane.wormholes.portal.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ConstructionGateTest {
    @AfterEach
    public void clearGuard() {
        AccessGuards.clear();
    }

    @Test
    public void aDenyingGuardStopsConstructionBeforeAnyRegionHop() {
        AtomicInteger asked = new AtomicInteger();
        AccessGuards.install(refusingGuard(asked));
        ConstructionManager manager = new ConstructionManager();
        Set<Block> cells = cells(3);

        boolean started = manager.startConstruct(UUID.randomUUID(), cells, PortalType.PORTAL, null, opened -> {
            throw new IllegalStateException("construction must not settle when the guard refuses");
        });

        assertFalse(started);
        assertEquals(1, asked.get());
    }

    @Test
    public void theGuardSeesEveryFrameCellAndTheOwner() {
        UUID ownerId = UUID.randomUUID();
        RecordingGuard guard = new RecordingGuard();
        AccessGuards.install(guard);
        ConstructionManager manager = new ConstructionManager();
        Set<Block> cells = cells(4);

        manager.startConstruct(ownerId, cells, PortalType.WORMHOLE, null, opened -> {
        });

        assertEquals(ownerId, guard.ownerId);
        assertEquals(cells, guard.cells);
        assertSame(PortalType.WORMHOLE, guard.type);
    }

    @Test
    public void anEmptySelectionNeverReachesTheGuard() {
        AtomicInteger asked = new AtomicInteger();
        AccessGuards.install(refusingGuard(asked));
        ConstructionManager manager = new ConstructionManager();

        assertFalse(manager.startConstruct(UUID.randomUUID(), Set.of(), PortalType.PORTAL, null, opened -> {
        }));
        assertEquals(0, asked.get());
    }

    @Test
    public void withNoGuardInstalledConstructionIsUnchanged() {
        assertTrue(AccessGuards.allowConstruct(UUID.randomUUID(), cells(1), PortalType.PORTAL));
        assertTrue(AccessGuards.allowPlacement(UUID.randomUUID(), null, List.of(new int[] {0, 64, 0}), PlacementKind.CREATE));
    }

    private static AccessGuard refusingGuard(AtomicInteger asked) {
        return new AccessGuard() {
            @Override
            public boolean allowConstruct(UUID ownerId, Set<Block> cells, PortalType type) {
                asked.incrementAndGet();
                return false;
            }

            @Override
            public boolean allowPlacement(UUID actorId, World world, List<int[]> cells, PlacementKind kind, String subject) {
                return false;
            }
        };
    }

    private static Set<Block> cells(int count) {
        Set<Block> cells = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            cells.add(block());
        }
        return cells;
    }

    private static Block block() {
        return (Block) Proxy.newProxyInstance(ConstructionGateTest.class.getClassLoader(), new Class<?>[] {Block.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "ConstructionGateTestBlock";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static final class RecordingGuard implements AccessGuard {
        private UUID ownerId;
        private Set<Block> cells;
        private PortalType type;

        @Override
        public boolean allowConstruct(UUID ownerId, Set<Block> cells, PortalType type) {
            this.ownerId = ownerId;
            this.cells = Set.copyOf(cells);
            this.type = type;
            return false;
        }

        @Override
        public boolean allowPlacement(UUID actorId, World world, List<int[]> cells, PlacementKind kind, String subject) {
            return true;
        }
    }
}
