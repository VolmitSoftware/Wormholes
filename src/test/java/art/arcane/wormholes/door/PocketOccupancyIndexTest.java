package art.arcane.wormholes.door;

import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sweep case from the headline review: pocket occupancy was polled off the global scheduler by
 * reading every online player's world and position, which is a foreign-region read on Folia and decides
 * whether a room gets wiped. It is now kept by the move handler, on the player's own thread.
 */
final class PocketOccupancyIndexTest {
    private static final UUID SPACE = new UUID(0, 4242);

    @Test
    void enteringAndLeavingAPocketMovesThePlayerInAndOutOfTheIndex() {
        PocketRulesListener listener = listener();
        Player player = player();

        listener.onMove(new PlayerMoveEvent(player, at(-1.0D), at(1.0D)));
        assertEquals(Set.of(SPACE), listener.occupiedSpaces());

        listener.onMove(new PlayerMoveEvent(player, at(1.0D), at(-1.0D)));
        assertTrue(listener.occupiedSpaces().isEmpty());
    }

    @Test
    void quittingInsideAPocketReleasesIt() {
        PocketRulesListener listener = listener();
        Player player = player();
        listener.onMove(new PlayerMoveEvent(player, at(-1.0D), at(1.0D)));

        listener.onQuit(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null));

        assertTrue(listener.occupiedSpaces().isEmpty());
    }

    /** Positive X is inside the pocket, anything else is outside it. */
    private static PocketRulesListener listener() {
        PocketSpace space = new PocketSpace(SPACE, PocketBinding.personal(new UUID(0, 7)), 0L, 0, 64, 0,
            PocketShell.defaults());
        PocketRosterService roster = new PocketRosterService(() -> null, itemId -> Optional.empty());
        return new PocketRulesListener(
            (int blockX, int blockZ) -> blockX > 0 ? Optional.of(space) : Optional.empty(), roster);
    }

    private static Location at(double x) {
        return new Location(world(), x, 64.0D, 0.0D);
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(PocketOccupancyIndexTest.class.getClassLoader(),
            new Class<?>[] {World.class}, (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
                case "getKey" -> PocketWorldService.WORLD_KEY;
                case "getName" -> "pockets";
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "toString" -> "PocketOccupancyWorld";
                default -> null;
            });
    }

    private static Player player() {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(PocketOccupancyIndexTest.class.getClassLoader(),
            new Class<?>[] {Player.class}, (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "occupant";
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "toString" -> "PocketOccupancyPlayer";
                default -> null;
            });
    }
}
