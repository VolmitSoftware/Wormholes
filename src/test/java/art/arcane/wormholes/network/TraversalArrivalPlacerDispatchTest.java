package art.arcane.wormholes.network;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.TraversableType;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.util.Direction;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TraversalArrivalPlacerDispatchTest {
    @Test
    void arrivalPlacementIsPushedOffTheJoinEventStackBeforeItTeleports() {
        List<Long> delays = new ArrayList<>();
        TraversalArrivalPlacer placer = new TraversalArrivalPlacer(
            null,
            new PlayerHandoffAdmission(),
            new TraversalFailureLedger(),
            new TraversalNotices(),
            (entity, task, retired, delayTicks) -> {
                delays.add(Long.valueOf(delayTicks));
                return true;
            }
        );
        UUID playerId = UUID.randomUUID();

        placer.place(player(playerId), reservation(playerId), "join");

        assertEquals(List.of(Long.valueOf(1L)), delays,
            "the arrival teleport must never run inline on the PlayerJoinEvent stack");
    }

    private static PlayerHandoffAdmission.Reservation reservation(UUID playerId) {
        PlayerHandoffAdmission.Request request = new PlayerHandoffAdmission.Request(
            UUID.randomUUID(),
            playerId,
            "traveler",
            "beta",
            UUID.randomUUID(),
            false,
            WireTraversive.fromTraversive(traversive())
        );
        return new PlayerHandoffAdmission.Reservation(request, System.currentTimeMillis() + 60_000L, 1L);
    }

    private static Traversive traversive() {
        return new Traversive(
            null,
            TraversableType.PLAYER,
            PortalFrame.canonical(Direction.N),
            new Vector(0.0D, 64.0D, 0.0D),
            new Vector(0.0D, 64.0D, 0.0D),
            new Vector(0.0D, 0.0D, 1.0D),
            new Vector(0.0D, 0.0D, 1.0D)
        );
    }

    private static Player player(UUID playerId) {
        return (Player) Proxy.newProxyInstance(
            TraversalArrivalPlacerDispatchTest.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getName" -> "traveler";
                case "isOnline", "isValid" -> Boolean.TRUE;
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "ArrivalTestPlayer[" + playerId + "]";
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == float.class) {
            return Float.valueOf(0.0F);
        }
        return Double.valueOf(0.0D);
    }
}
