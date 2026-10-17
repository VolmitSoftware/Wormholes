package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.wormholes.menu.protocol.MenuProtocol;
import art.arcane.wormholes.menu.protocol.MenuServerInfo;
import net.minecraft.ChatFormatting;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WormholesDebugLinesTest {
    private static final WormholesDebugStatus.Activity ACTIVITY = new WormholesDebugStatus.Activity(2, 1, 3, 1,
        WormholesDebugStatus.Travel.ARMED);

    @Test
    public void disconnectedWithoutAWormholesServerShowsOnlyTheStatus() {
        WormholesDebugStatus status = new WormholesDebugStatus(ClientViewSession.ConnectionStatus.DISCONNECTED, "3.1.0", null,
            new WormholesDebugStatus.Protocols(0, false, false, 0), new WormholesDebugStatus.Activity(0, 0, 0, 0, WormholesDebugStatus.Travel.IDLE));
        assertEquals(List.of("Wormholes: Disconnected"), plain(WormholesDebugLines.summary(status)));
    }

    @Test
    public void nativeServerShowsVersionProtocolsActivityAndTravel() {
        WormholesDebugStatus status = new WormholesDebugStatus(ClientViewSession.ConnectionStatus.CONNECTED, "3.1.0",
            new MenuServerInfo("3.1.0", "fabric 26.1.2", true), new WormholesDebugStatus.Protocols(ViewStreamLimits.WIRE_VERSION, true, true,
            MenuProtocol.VERSION), ACTIVITY);
        List<String> lines = WormholesDebugLines.summary(status);
        assertEquals(List.of(
            "Wormholes: Connected | 3.1.0 fabric 26.1.2",
            "Protocols: view v" + ViewStreamLimits.WIRE_VERSION + ", travel on, menus v" + MenuProtocol.VERSION + " native",
            "Portals: armed 2, drawn 1, streams 3",
            "Travel: armed, residents 1"), plain(lines));
        assertEquals(ChatFormatting.GOLD + "Portals: " + ChatFormatting.RESET + "armed 2, drawn 1, streams 3", lines.get(2));
        for (String line : plain(lines)) {
            assertTrue(line, line.length() <= WormholesDebugLines.MAX_WIDTH);
        }
    }

    @Test
    public void bukkitServerShowsTravelOffAndChestMenus() {
        WormholesDebugStatus status = new WormholesDebugStatus(ClientViewSession.ConnectionStatus.CONNECTED, "3.1.0",
            new MenuServerInfo("3.1.0", "Paper 26.1.2", false), new WormholesDebugStatus.Protocols(ViewStreamLimits.WIRE_VERSION, true, false,
            MenuProtocol.VERSION), new WormholesDebugStatus.Activity(0, 2, 2, 0, WormholesDebugStatus.Travel.IDLE));
        assertEquals(List.of(
            "Wormholes: Connected | 3.1.0 Paper 26.1.2",
            "Protocols: view v" + ViewStreamLimits.WIRE_VERSION + ", travel off, menus v" + MenuProtocol.VERSION + " chest",
            "Portals: armed 0, drawn 2, streams 2",
            "Travel: idle, residents 0"), plain(WormholesDebugLines.summary(status)));
    }

    @Test
    public void connectedWithoutMenuHandshakeShowsAnUnknownServer() {
        WormholesDebugStatus status = new WormholesDebugStatus(ClientViewSession.ConnectionStatus.CONNECTED, "3.1.0", null,
            new WormholesDebugStatus.Protocols(ViewStreamLimits.WIRE_VERSION, true, false, 0), ACTIVITY);
        List<String> lines = plain(WormholesDebugLines.summary(status));
        assertEquals("Wormholes: Connected | server unknown", lines.get(0));
        assertEquals("Protocols: view v" + ViewStreamLimits.WIRE_VERSION + ", travel off, menus off", lines.get(1));
        assertEquals(4, lines.size());
    }

    @Test
    public void eachMismatchAddsAYellowWarningLine() {
        WormholesDebugStatus status = new WormholesDebugStatus(ClientViewSession.ConnectionStatus.MISMATCH, "3.1.0",
            new MenuServerInfo("3.0.4", "Paper 26.1.2", true), new WormholesDebugStatus.Protocols(ViewStreamLimits.WIRE_VERSION + 1, false, false,
            MenuProtocol.VERSION + 1), ACTIVITY);
        List<String> lines = WormholesDebugLines.summary(status);
        assertEquals(7, lines.size());
        assertEquals("Protocols: view off, travel off, menus v" + (MenuProtocol.VERSION + 1) + " native", plain(lines).get(1));
        assertEquals(ChatFormatting.YELLOW + "Mismatch: version client 3.1.0, server 3.0.4" + ChatFormatting.RESET, lines.get(4));
        assertEquals("Mismatch: view client v" + ViewStreamLimits.WIRE_VERSION + ", server v" + (ViewStreamLimits.WIRE_VERSION + 1),
            plain(lines).get(5));
        assertEquals("Mismatch: menus client v" + MenuProtocol.VERSION + ", server v" + (MenuProtocol.VERSION + 1), plain(lines).get(6));
    }

    @Test
    public void dataVersionMismatchIsNamedWhenTheWireMatches() {
        WormholesDebugStatus status = new WormholesDebugStatus(ClientViewSession.ConnectionStatus.MISMATCH, "3.1.0",
            new MenuServerInfo("3.1.0", "neoforge 26.3", true), new WormholesDebugStatus.Protocols(ViewStreamLimits.WIRE_VERSION, false, false,
            MenuProtocol.VERSION), ACTIVITY);
        List<String> lines = plain(WormholesDebugLines.summary(status));
        assertEquals("Wormholes: Mismatch | 3.1.0 neoforge 26.3", lines.get(0));
        assertEquals(List.of("Mismatch: Minecraft data version"), lines.subList(4, lines.size()));
    }

    @Test
    public void detailSectionsWrapAtWordsWithIndentedContinuations() {
        List<String> lines = WormholesDebugLines.wrap(List.of(
            "Stream: frames=429 bytes=5210755 sweep=12us apply=4us unknown=0 entities=2 fx=2", "Short: one"));
        assertEquals(List.of("Stream: frames=429 bytes=5210755 sweep=12us", "  apply=4us unknown=0 entities=2 fx=2", "Short: one"), lines);
        for (String line : lines) {
            assertTrue(line, line.length() <= WormholesDebugLines.MAX_WIDTH);
        }
    }

    @Test
    public void everyTravelStateHasALabel() {
        assertEquals("idle", WormholesDebugStatus.Travel.IDLE.label());
        assertEquals("armed", WormholesDebugStatus.Travel.ARMED.label());
        assertEquals("awaiting server", WormholesDebugStatus.Travel.AWAITING.label());
        assertEquals("crossing", WormholesDebugStatus.Travel.CROSSING.label());
    }

    private static List<String> plain(List<String> lines) {
        List<String> plain = new ArrayList<>(lines.size());
        for (String line : lines) {
            plain.add(ChatFormatting.stripFormatting(line));
        }
        return plain;
    }
}
