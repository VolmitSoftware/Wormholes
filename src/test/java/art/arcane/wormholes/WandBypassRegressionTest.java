package art.arcane.wormholes;

import art.arcane.wormholes.access.AccessGuard;
import art.arcane.wormholes.access.AccessGuards;
import art.arcane.wormholes.access.PlacementKind;
import art.arcane.wormholes.portal.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class WandBypassRegressionTest {
    @AfterEach
    public void clearGuard() {
        AccessGuards.clear();
    }

    @Test
    public void theWandHandlerReceivesAirClicksCancelledByBukkit() throws NoSuchMethodException {
        Method handler = WandSelectionManager.class.getMethod("on", PlayerInteractEvent.class);
        EventHandler annotation = handler.getAnnotation(EventHandler.class);
        PlayerInteractEvent inAir = new PlayerInteractEvent(player(), Action.LEFT_CLICK_AIR,
            null, null, BlockFace.SELF);

        assertSame(EventPriority.HIGH, annotation.priority());
        assertTrue(inAir.isCancelled());
        assertFalse(annotation.ignoreCancelled(), "Bukkit pre-cancels air clicks before listener dispatch");
    }

    @Test
    public void aDeniedBlockClickIsRefusedButAnAirClickCarriesNoProtectionSignal() {
        PlayerInteractEvent onBlock = new PlayerInteractEvent(player(), Action.LEFT_CLICK_BLOCK,
            null, block(), BlockFace.NORTH);
        onBlock.setUseInteractedBlock(Event.Result.DENY);
        PlayerInteractEvent inAir = new PlayerInteractEvent(player(), Action.LEFT_CLICK_AIR,
            null, null, BlockFace.SELF);

        assertTrue(WandSelectionManager.protectionDenied(onBlock));
        assertFalse(WandSelectionManager.protectionDenied(inAir),
            "Bukkit denies the absent block by default, so an air click must not be read as protection");
    }

    /**
     * An air-click build has no block event to protect it, so the construct guard is what covers it -
     * and it is the only pass over the selection, not a second one after a duplicate placement check.
     */
    @Test
    public void anAirClickBuildOffersEveryCellOfTheSelectionToTheConstructGuardOnce() {
        RecordingGuard guard = new RecordingGuard();
        AccessGuards.install(guard);
        Set<Block> selection = new LinkedHashSet<>();
        for (int index = 0; index < 9; index++) {
            selection.add(block());
        }

        boolean opened = new ConstructionManager().startConstruct(UUID.randomUUID(), selection,
            PortalType.PORTAL, new Vector(0.0D, 0.0D, 1.0D), settled -> { });

        assertFalse(opened);
        assertEquals(1, guard.constructCalls);
        assertEquals(9, guard.constructCells.size());
        assertTrue(guard.cells.isEmpty(), "the selection is not run through the placement policy a second time");
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(WandBypassRegressionTest.class.getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> UUID.randomUUID();
                case "getName" -> "Builder";
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "WandBypassTestPlayer";
                default -> null;
            });
    }

    private static Block block() {
        return (Block) Proxy.newProxyInstance(WandBypassRegressionTest.class.getClassLoader(), new Class<?>[] {Block.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "WandBypassTestBlock";
                default -> null;
            });
    }

    private static final class RecordingGuard implements AccessGuard {
        private List<int[]> cells = new ArrayList<>();
        private Set<Block> constructCells = Set.of();
        private int constructCalls;
        private PlacementKind kind;

        @Override
        public boolean allowConstruct(UUID ownerId, Set<Block> cells, PortalType type) {
            constructCalls++;
            constructCells = Set.copyOf(cells);
            return false;
        }

        @Override
        public boolean allowPlacement(UUID actorId, World world, List<int[]> requested, PlacementKind requestedKind, String subject) {
            cells = List.copyOf(requested);
            kind = requestedKind;
            return false;
        }
    }
}
