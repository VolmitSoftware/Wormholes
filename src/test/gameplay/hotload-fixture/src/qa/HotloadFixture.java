package qa;

import art.arcane.wormholes.Wormholes;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;

public final class HotloadFixture extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player && player.isOp()) {
            getServer().getGlobalRegionScheduler().execute(this, () -> reportMetrics(player));
        }
        return true;
    }

    private void reportMetrics(Player player) {
        String result;
        try {
            Object diagnostics = field(Wormholes.INSTANCE, "diagnostics");
            Object runtime = field(diagnostics, "metricsRuntime");
            if (runtime == null) {
                result = "FIXTURE metrics configured=" + Wormholes.settings.isMetrics() + " runtime=false";
            } else {
                Object base = field(field(runtime, "metrics"), "metricsBase");
                ScheduledExecutorService scheduler = (ScheduledExecutorService) field(base, "scheduler");
                result = "FIXTURE metrics configured=" + Wormholes.settings.isMetrics()
                    + " runtime=true enabled=" + field(base, "enabled")
                    + " stopped=" + scheduler.isShutdown();
            }
        } catch (ReflectiveOperationException failure) {
            getLogger().log(Level.SEVERE, "Could not inspect Wormholes metrics runtime", failure);
            result = "FIXTURE metrics inspection failed";
        }
        String message = result;
        player.getScheduler().execute(this, () -> player.sendMessage(message), null, 1L);
    }

    private Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
