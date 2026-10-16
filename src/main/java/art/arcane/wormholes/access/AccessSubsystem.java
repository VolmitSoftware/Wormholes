package art.arcane.wormholes.access;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.access.adapters.ReflectiveEnvironment;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.AccessConfig;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.hook.WormholesSubsystem;
import art.arcane.wormholes.localization.AccessMessages;
import art.arcane.wormholes.menu.access.AccessRoutes;
import art.arcane.wormholes.menu.host.BukkitMenuEntries;
import art.arcane.wormholes.menu.portal.MenuExtensionEntry;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;

/** Lifecycle entry point for the access lane: portal roles, permission keys, claims, and limits. */
public final class AccessSubsystem implements WormholesSubsystem {
    private final ClaimAdapters claimAdapters = new ClaimAdapters(ReflectiveEnvironment.bukkit());
    private final ClaimPluginWatcher claimPluginWatcher = new ClaimPluginWatcher(claimAdapters);
    private final AccessGate gate = new AccessGate(claimAdapters);
    private final ClaimAccessGuard guard = new ClaimAccessGuard(claimAdapters);

    @Override
    public String id() {
        return "access";
    }

    @Override
    public void register(WormholesRegistrar registrar) {
        registrar.portalExtension(AccessPortalExtension.class, AccessPortalExtension::new);
        registrar.traversalGate(gate);
        registrar.menuEntry(new MenuExtensionEntry("access", "minecraft:name_tag", "access", AccessMessages.MENU_ENTRY, 0,
            portalId -> MessageArgs.empty(), BukkitMenuEntries.always(), BukkitMenuEntries.when(AccessSubsystem::hasRoles), AccessRoutes::roles));
    }

    @Override
    public void start(Wormholes plugin) {
        applySettings(Wormholes.settings);
        AccessGuards.install(guard);
        Bukkit.getPluginManager().registerEvents(claimPluginWatcher, plugin);
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(claimPluginWatcher);
        AccessGuards.clear();
        gate.clear();
    }

    @Override
    public void onSettingsReloaded(WormholesSettings settings) {
        applySettings(settings);
    }

    private static boolean hasRoles(LocalPortal portal) {
        AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
        return access != null && !access.roles().isEmpty();
    }

    private void applySettings(WormholesSettings settings) {
        AccessConfig access = settings == null ? new AccessConfig() : settings.getAccess();
        gate.applySettings(access);
        guard.applySettings(access);
        claimAdapters.configure(access.claimAdapters, access.worldguardFlagsEnabled);
    }
}
