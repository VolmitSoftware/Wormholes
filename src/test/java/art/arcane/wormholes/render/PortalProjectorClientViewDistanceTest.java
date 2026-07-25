package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

public final class PortalProjectorClientViewDistanceTest {
    @Test
    public void aPlatformThatCannotAnswerIsAskedOnceAndThenLeftAlone() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        Player observer = observer(invocations, null);
        ProjectorViewFrustum frustum = new ProjectorViewFrustum(Player.class.getMethod("getFoodLevel"));

        assertEquals(0, frustum.clientViewDistance(observer),
            "an unusable client view distance must fall back to the server view distance");
        assertEquals(1, invocations.get());
        assertTrue(frustum.clientViewDistanceFailed(),
            "the failure must be recorded so it is distinguishable from a client reporting zero");

        assertEquals(0, frustum.clientViewDistance(observer));
        assertEquals(1, invocations.get(),
            "a platform whose reflective call throws must not be re-asked on every projection pass");
    }

    @Test
    public void aWorkingPlatformKeepsAnsweringEveryPass() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        Player observer = observer(invocations, Integer.valueOf(9));
        ProjectorViewFrustum frustum = new ProjectorViewFrustum(Player.class.getMethod("getFoodLevel"));

        assertEquals(9, frustum.clientViewDistance(observer));
        assertEquals(9, frustum.clientViewDistance(observer));
        assertEquals(2, invocations.get());
        assertFalse(frustum.clientViewDistanceFailed());
    }

    @Test
    public void aPlatformWithoutTheMethodAtAllStaysSilentAndUsesTheServerDistance() {
        AtomicInteger invocations = new AtomicInteger();
        Player observer = observer(invocations, Integer.valueOf(9));
        ProjectorViewFrustum frustum = new ProjectorViewFrustum(null);

        assertEquals(0, frustum.clientViewDistance(observer));
        assertEquals(0, invocations.get());
        assertFalse(frustum.clientViewDistanceFailed(),
            "an absent method is a platform capability, not a runtime failure");
    }

    private static Player observer(AtomicInteger invocations, Integer answer) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, args) -> {
                if ("getFoodLevel".equals(method.getName())) {
                    invocations.incrementAndGet();
                    if (answer == null) {
                        throw new UnsupportedOperationException("client view distance is unavailable here");
                    }
                    return answer;
                }
                return switch (method.getName()) {
                    case "getName", "toString" -> "client-view-distance-observer";
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                    case "equals" -> Boolean.valueOf(proxy == args[0]);
                    default -> null;
                };
            });
    }
}
