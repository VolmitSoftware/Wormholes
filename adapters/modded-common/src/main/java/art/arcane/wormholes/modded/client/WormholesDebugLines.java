package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.wormholes.menu.protocol.MenuProtocol;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;

public final class WormholesDebugLines {
    public static final int MAX_WIDTH = 48;
    private static final String CONTINUATION = "  ";
    private static final int SUMMARY_LINES = 7;

    private WormholesDebugLines() {
    }

    public static List<String> summary(WormholesDebugStatus status) {
        String connection = status.connection().debugLine();
        if (!status.serverKnown() && status.connection() == ClientViewSession.ConnectionStatus.DISCONNECTED) {
            return List.of(connection);
        }
        List<String> lines = new ArrayList<>(SUMMARY_LINES);
        lines.add(connection + " | " + server(status));
        lines.add(label("Protocols") + protocols(status));
        WormholesDebugStatus.Activity activity = status.activity();
        lines.add(label("Portals") + "armed " + activity.armed() + ", drawn " + activity.drawn() + ", streams " + activity.streams());
        lines.add(label("Travel") + activity.travel().label() + ", residents " + activity.residents());
        mismatches(status, lines);
        return List.copyOf(lines);
    }

    public static List<String> wrap(List<String> sections) {
        List<String> lines = new ArrayList<>(sections.size() * 2);
        for (String section : sections) {
            wrap(section, lines);
        }
        return List.copyOf(lines);
    }

    private static void wrap(String section, List<String> lines) {
        StringBuilder line = new StringBuilder(MAX_WIDTH);
        int start = 0;
        for (String word : section.split(" ")) {
            if (line.length() > start && line.length() + 1 + word.length() > MAX_WIDTH) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(CONTINUATION);
                start = CONTINUATION.length();
            }
            if (line.length() > start) {
                line.append(' ');
            }
            line.append(word);
        }
        lines.add(line.toString());
    }

    private static String label(String name) {
        return ChatFormatting.GOLD + name + ": " + ChatFormatting.RESET;
    }

    private static String server(WormholesDebugStatus status) {
        if (!status.serverKnown()) {
            return "server unknown";
        }
        return status.server().wormholesVersion() + " " + status.server().platform();
    }

    private static String protocols(WormholesDebugStatus status) {
        WormholesDebugStatus.Protocols protocols = status.protocols();
        String view = protocols.view() ? "view v" + protocols.viewWire() : "view off";
        String travel = protocols.travel() ? "travel on" : "travel off";
        String menus = protocols.menus() < 1 ? "menus off"
            : "menus v" + protocols.menus() + (status.serverKnown() && status.server().nativeScreens() ? " native" : " chest");
        return view + ", " + travel + ", " + menus;
    }

    private static void mismatches(WormholesDebugStatus status, List<String> lines) {
        if (status.serverKnown() && !status.server().wormholesVersion().equals(status.clientVersion())) {
            lines.add(mismatch("version client " + status.clientVersion() + ", server " + status.server().wormholesVersion()));
        }
        int viewWire = status.protocols().viewWire();
        if (viewWire > 0 && viewWire != ViewStreamLimits.WIRE_VERSION) {
            lines.add(mismatch("view client v" + ViewStreamLimits.WIRE_VERSION + ", server v" + viewWire));
        } else if (status.connection() == ClientViewSession.ConnectionStatus.MISMATCH) {
            lines.add(mismatch("Minecraft data version"));
        }
        int menus = status.protocols().menus();
        if (menus > 0 && menus != MenuProtocol.VERSION) {
            lines.add(mismatch("menus client v" + MenuProtocol.VERSION + ", server v" + menus));
        }
    }

    private static String mismatch(String detail) {
        return ChatFormatting.YELLOW + "Mismatch: " + detail + ChatFormatting.RESET;
    }
}
