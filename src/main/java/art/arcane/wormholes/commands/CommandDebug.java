package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.service.WormholesAudience;
import org.bukkit.command.CommandSender;

@Director(name = "debug", description = "Wormholes diagnostic tools", descriptionKey = "command.help.debug")
public final class CommandDebug {
    private final Wormholes plugin;

    public CommandDebug(Wormholes plugin) {
        this.plugin = plugin;
    }

    @Director(name = "dump", sync = true, description = "Create and optionally upload a diagnostic report", descriptionKey = "command.help.debug_dump")
    public void dump(
            @Param(name = "upload", defaultValue = "true", description = "Upload the report to mclo.gs", descriptionKey = "command.help.debug_dump_upload") boolean upload,
            @Param(name = "sender", contextual = true) CommandSender sender
    ) {
        plugin.debugDump().request(sender, upload);
    }

    @Director(name = "toggle", sync = true, description = "Toggle verbose console logs and one-second telemetry", descriptionKey = "command.help.debug_toggle")
    public void toggle(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin")) {
            WormholesAudience.sendMessage(sender, Wormholes.text().component(sender, WormholesMessages.COMMAND_NO_PERMISSION));
            return;
        }
        plugin.toggleDebugTelemetry(sender.getName());
    }
}
