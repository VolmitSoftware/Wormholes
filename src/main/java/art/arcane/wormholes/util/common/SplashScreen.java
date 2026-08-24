package art.arcane.wormholes.util.common;

import art.arcane.volmlib.util.plugin.ComponentLog;
import art.arcane.volmlib.util.plugin.SplashScreenSupport;
import art.arcane.wormholes.Wormholes;
import net.md_5.bungee.api.ChatColor;

import java.util.logging.Level;

public final class SplashScreen {
    private static final String SUPPORTED_MC_VERSION = "26.1.2 - 26.2";

    private SplashScreen() {
    }

    public static void print(Wormholes plugin, boolean success, String errorMessage) {
        ChatColor dark = ChatColor.of("#1c1a14");
        ChatColor accent = ChatColor.of("#d4af37");
        ChatColor meta = ChatColor.of("#8c7a45");
        ChatColor statusColor = success ? ChatColor.GREEN : ChatColor.RED;
        String status = success ? "READY" : "DEGRADED";
        String pluginVersion = plugin.getDescription().getVersion();
        String releaseTrain = SplashScreenSupport.releaseTrain(pluginVersion);
        String serverVersion = SplashScreenSupport.serverVersionWithoutMcSuffix();
        String startupDate = SplashScreenSupport.startupDate();

        String splash =
            "\n"
                + dark + "██" + accent + "╗    " + dark + "██" + accent + "╗ " + dark + "██████" + accent + "╗ " + dark + "██████" + accent + "╗ " + dark + "███" + accent + "╗   " + dark + "███" + accent + "╗" + dark + "██" + accent + "╗  " + dark + "██" + accent + "╗ " + dark + "██████" + accent + "╗ " + dark + "██" + accent + "╗     " + dark + "███████" + accent + "╗" + dark + "███████" + accent + "╗\n"
                + dark + "██" + accent + "║    " + dark + "██" + accent + "║" + dark + "██" + accent + "╔═══" + dark + "██" + accent + "╗" + dark + "██" + accent + "╔══" + dark + "██" + accent + "╗" + dark + "████" + accent + "╗ " + dark + "████" + accent + "║" + dark + "██" + accent + "║  " + dark + "██" + accent + "║" + dark + "██" + accent + "╔═══" + dark + "██" + accent + "╗" + dark + "██" + accent + "║     " + dark + "██" + accent + "╔════╝" + dark + "██" + accent + "╔════╝" + accent + "   Wormholes, " + meta + "Through-Portal Projection " + ChatColor.GOLD + "[" + releaseTrain + " RELEASE]\n"
                + dark + "██" + accent + "║ " + dark + "█" + accent + "╗ " + dark + "██" + accent + "║" + dark + "██" + accent + "║   " + dark + "██" + accent + "║" + dark + "██████" + accent + "╔╝" + dark + "██" + accent + "╔" + dark + "████" + accent + "╔" + dark + "██" + accent + "║" + dark + "███████" + accent + "║" + dark + "██" + accent + "║   " + dark + "██" + accent + "║" + dark + "██" + accent + "║     " + dark + "█████" + accent + "╗  " + dark + "███████" + accent + "╗" + meta + "   Version: " + accent + pluginVersion + "\n"
                + dark + "██" + accent + "║" + dark + "███" + accent + "╗" + dark + "██" + accent + "║" + dark + "██" + accent + "║   " + dark + "██" + accent + "║" + dark + "██" + accent + "╔══" + dark + "██" + accent + "╗" + dark + "██" + accent + "║╚" + dark + "██" + accent + "╔╝" + dark + "██" + accent + "║" + dark + "██" + accent + "╔══" + dark + "██" + accent + "║" + dark + "██" + accent + "║   " + dark + "██" + accent + "║" + dark + "██" + accent + "║     " + dark + "██" + accent + "╔══╝  ╚════" + dark + "██" + accent + "║" + meta + "   By: " + accent + "Volmit Software (Arcane Arts)" + meta + " | " + accent + "VolmitSoftware.com" + meta + " | Startup: " + statusColor + status + "\n"
                + accent + "╚" + dark + "███" + accent + "╔" + dark + "███" + accent + "╔╝╚" + dark + "██████" + accent + "╔╝" + dark + "██" + accent + "║  " + dark + "██" + accent + "║" + dark + "██" + accent + "║ ╚═╝ " + dark + "██" + accent + "║" + dark + "██" + accent + "║  " + dark + "██" + accent + "║╚" + dark + "██████" + accent + "╔╝" + dark + "███████" + accent + "╗" + dark + "███████" + accent + "╗" + dark + "███████" + accent + "║" + meta + "   Server: " + accent + serverVersion + meta + " | MC Support: " + accent + SUPPORTED_MC_VERSION + "\n"
                + accent + " ╚══╝╚══╝  ╚═════╝ ╚═╝  ╚═╝╚═╝     ╚═╝╚═╝  ╚═╝ ╚═════╝ ╚══════╝╚══════╝╚══════╝" + meta + "   Java: " + accent + SplashScreenSupport.javaMajorVersion() + meta + " | Date: " + accent + startupDate + "\n";

        ComponentLog.logLegacy(plugin, plugin.getLogger(), "[Wormholes] ", Level.INFO, splash, null);
        if (!success && errorMessage != null && !errorMessage.isBlank()) {
            plugin.getLogger().warning("Startup error: " + errorMessage);
        }
    }
}
