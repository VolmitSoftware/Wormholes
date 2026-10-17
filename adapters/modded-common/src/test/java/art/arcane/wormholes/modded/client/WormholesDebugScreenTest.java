package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.menu.protocol.MenuServerInfo;
import net.minecraft.ChatFormatting;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

public class WormholesDebugScreenTest {
    @Test
    public void summaryIsSampledOncePerTickAndReusedWhileUnchanged() {
        AtomicInteger samples = new AtomicInteger();
        AtomicReference<WormholesDebugStatus> current = new AtomicReference<>(status(1));
        WormholesDebugScreen screen = new WormholesDebugScreen(() -> {
            samples.incrementAndGet();
            return current.get();
        }, List::of);
        List<String> first = screen.summary(1L);
        assertSame(first, screen.summary(1L));
        assertEquals(1, samples.get());
        assertSame(first, screen.summary(2L));
        assertEquals(2, samples.get());
        current.set(status(2));
        List<String> changed = screen.summary(3L);
        assertNotSame(first, changed);
        assertEquals("Portals: armed 2, drawn 0, streams 0", ChatFormatting.stripFormatting(changed.get(2)));
    }

    @Test
    public void detailIsBuiltOncePerTick() {
        AtomicInteger builds = new AtomicInteger();
        WormholesDebugScreen screen = new WormholesDebugScreen(() -> status(0), () -> List.of("detail " + builds.incrementAndGet()));
        assertEquals(List.of("detail 1"), screen.detail(5L));
        assertEquals(List.of("detail 1"), screen.detail(5L));
        assertEquals(List.of("detail 2"), screen.detail(6L));
    }

    private static WormholesDebugStatus status(int armed) {
        return new WormholesDebugStatus(ClientViewSession.ConnectionStatus.CONNECTED, "3.1.0", new MenuServerInfo("3.1.0", "fabric 26.1.2", true),
            new WormholesDebugStatus.Protocols(7, true, true, 1),
            new WormholesDebugStatus.Activity(armed, 0, 0, 0, armed > 0 ? WormholesDebugStatus.Travel.ARMED : WormholesDebugStatus.Travel.IDLE));
    }
}
