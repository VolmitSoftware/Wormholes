package art.arcane.wormholes.service;

import art.arcane.wormholes.Wormholes;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;

import java.util.Collection;
import java.util.List;

import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;

final class PaperCommandRegistrar {
    private static final String ROOT_COMMAND = "wormholes";

    private PaperCommandRegistrar() {
    }

    /** Registers the player-facing atlas root command on the Paper plugin path. */
    static void registerAtlas(Wormholes plugin, String name, String description, List<String> aliases,
                              String permission, CommandExecutor executor, TabCompleter completer) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register(name, description, aliases,
                new PaperExecutorCommand(name, permission, executor, completer))
        );
    }

    static void register(Wormholes plugin, WormholesCommandService commandService) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register(
                ROOT_COMMAND,
                "Wormholes base command.",
                List.of("wh", "wormhole"),
                new PaperCommand(commandService)
            )
        );
    }

    private record PaperExecutorCommand(String name, String permission, CommandExecutor executor,
                                        TabCompleter completer) implements BasicCommand {
        @Override
        public void execute(CommandSourceStack source, String[] args) {
            executor.onCommand(source.getSender(), new PaperPlaceholderCommand(name), name, args);
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            List<String> suggestions = completer.onTabComplete(source.getSender(), new PaperPlaceholderCommand(name), name, args);
            return suggestions == null ? List.of() : suggestions;
        }

        @Override
        public boolean canUse(CommandSender sender) {
            return permission == null || sender.hasPermission(permission);
        }
    }

    /** Carries only the command name; the Bukkit executors this wraps read nothing else from it. */
    private static final class PaperPlaceholderCommand extends org.bukkit.command.Command {
        private PaperPlaceholderCommand(String name) {
            super(name);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            return false;
        }
    }

    private record PaperCommand(WormholesCommandService commandService) implements BasicCommand {
        @Override
        public void execute(CommandSourceStack source, String[] args) {
            commandService.executeCommand(source.getSender(), ROOT_COMMAND, args);
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            return commandService.tabComplete(source.getSender(), ROOT_COMMAND, args);
        }

        @Override
        public boolean canUse(CommandSender sender) {
            return true;
        }
    }
}
