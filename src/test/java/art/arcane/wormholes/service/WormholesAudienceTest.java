package art.arcane.wormholes.service;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WormholesAudienceTest {
    @BeforeEach
    void resetTelemetry() {
        WormholesTelemetry.clear();
    }

    @AfterEach
    void clearTelemetry() {
        WormholesTelemetry.clear();
    }

    @Test
    void undeliverableMessageIsCountedAndStillReachesTheCaller() {
        CommandSender sender = throwingSender();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> WormholesAudience.sendMessage(sender, Component.text("bounced")));

        assertEquals("sender is gone", thrown.getMessage());
        assertEquals(1L, WormholesTelemetry.failures());
        assertEquals(Long.valueOf(1L), WormholesTelemetry.failureBreakdown().get("AUDIENCE_MESSAGE_DELIVERY_FAILED"));
    }

    @Test
    void deliveredMessageCountsNoFailure() {
        List<String> delivered = new ArrayList<>();
        CommandSender sender = recordingSender(delivered);

        WormholesAudience.sendMessage(sender, Component.text("bounced"));

        assertEquals(0L, WormholesTelemetry.failures());
        assertEquals(1, delivered.size());
    }

    private static CommandSender throwingSender() {
        return (CommandSender) Proxy.newProxyInstance(
            WormholesAudienceTest.class.getClassLoader(),
            new Class<?>[]{CommandSender.class},
            (proxy, method, args) -> {
                if (method.getName().equals("sendMessage")) {
                    throw new IllegalStateException("sender is gone");
                }
                return defaultValue(method.getReturnType());
            });
    }

    private static CommandSender recordingSender(List<String> delivered) {
        return (CommandSender) Proxy.newProxyInstance(
            WormholesAudienceTest.class.getClassLoader(),
            new Class<?>[]{CommandSender.class},
            (proxy, method, args) -> {
                if (method.getName().equals("sendMessage") && args != null && args.length > 0) {
                    delivered.add(String.valueOf(args[0]));
                    return null;
                }
                return defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return Boolean.FALSE;
        }
        if (returnType == int.class) {
            return Integer.valueOf(0);
        }
        return null;
    }
}
