package art.arcane.wormholes.atlas;

import art.arcane.wormholes.Wormholes;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Installs {@code /atlas}. Bukkit plugins get the command declared in plugin.yml; Paper plugins
 * register it through the lifecycle registrar, which is reached reflectively so the Spigot
 * compatibility compile never sees Paper's command API.
 */
final class AtlasCommandInstaller {
    private static final String DESCRIPTION = "Open your portal atlas.";
    private static final List<String> ALIASES = List.of("portals");

    private AtlasCommandInstaller() {
    }

    static void install(Wormholes plugin, CommandAtlas command) {
        if (command == null) {
            return;
        }
        PluginCommand declared = bukkitCommand(plugin);
        if (declared != null) {
            declared.setExecutor(command);
            declared.setTabCompleter(command);
            return;
        }
        registerPaperCommand(plugin, command, command);
    }

    private static PluginCommand bukkitCommand(Wormholes plugin) {
        try {
            return plugin.getCommand(CommandAtlas.NAME);
        } catch (UnsupportedOperationException paperPlugin) {
            return null;
        }
    }

    private static void registerPaperCommand(Wormholes plugin, CommandExecutor executor, TabCompleter completer) {
        try {
            Class<?> registrar = Class.forName("art.arcane.wormholes.service.PaperCommandRegistrar",
                    true, AtlasCommandInstaller.class.getClassLoader());
            Method register = registrar.getDeclaredMethod("registerAtlas", Wormholes.class, String.class, String.class,
                    List.class, String.class, CommandExecutor.class, TabCompleter.class);
            register.setAccessible(true);
            register.invoke(null, plugin, CommandAtlas.NAME, DESCRIPTION, ALIASES, CommandAtlas.PERMISSION,
                    executor, completer);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException("Atlas command registration failed", cause);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("Paper command registrar is unavailable for /atlas", failure);
        }
    }
}
