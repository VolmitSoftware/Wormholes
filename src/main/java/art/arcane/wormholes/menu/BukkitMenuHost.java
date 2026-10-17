package art.arcane.wormholes.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.WormholesBuild;
import art.arcane.wormholes.atlas.AtlasRuntime;
import art.arcane.wormholes.atlas.AtlasService;
import art.arcane.wormholes.menu.access.AccessMenuController;
import art.arcane.wormholes.menu.atlas.AtlasMenuController;
import art.arcane.wormholes.menu.cosmetics.CosmeticsMenuController;
import art.arcane.wormholes.menu.cost.CostMenuController;
import art.arcane.wormholes.menu.destination.DestinationMenuController;
import art.arcane.wormholes.menu.door.DoorAccessMenuController;
import art.arcane.wormholes.menu.extensions.ExtensionsMenuController;
import art.arcane.wormholes.menu.fidelity.FidelityMenuController;
import art.arcane.wormholes.menu.host.BukkitAccessMenuHost;
import art.arcane.wormholes.menu.host.BukkitAtlasMenuHost;
import art.arcane.wormholes.menu.host.BukkitCosmeticsMenuHost;
import art.arcane.wormholes.menu.host.BukkitCostMenuHost;
import art.arcane.wormholes.menu.host.BukkitDestinationMenuHost;
import art.arcane.wormholes.menu.host.BukkitDialMenuHost;
import art.arcane.wormholes.menu.host.BukkitDoorAccessMenuHost;
import art.arcane.wormholes.menu.host.BukkitExtensionsMenuHost;
import art.arcane.wormholes.menu.host.BukkitFidelityMenuHost;
import art.arcane.wormholes.menu.host.BukkitGatewayMenuHost;
import art.arcane.wormholes.menu.host.BukkitLanguageMenuHost;
import art.arcane.wormholes.menu.host.BukkitNetworkMenuHost;
import art.arcane.wormholes.menu.host.BukkitPortalMenuHost;
import art.arcane.wormholes.menu.host.BukkitRtpMenuHost;
import art.arcane.wormholes.menu.host.BukkitRulesMenuHost;
import art.arcane.wormholes.menu.host.BukkitTransitMenuHost;
import art.arcane.wormholes.menu.language.LanguageMenuController;
import art.arcane.wormholes.menu.language.LanguageMenuView;
import art.arcane.wormholes.menu.language.LanguageRoutes;
import art.arcane.wormholes.menu.nexus.DialMenuController;
import art.arcane.wormholes.menu.nexus.NetworkMenuController;
import art.arcane.wormholes.menu.portal.PortalMenuController;
import art.arcane.wormholes.menu.protocol.MenuServerInfo;
import art.arcane.wormholes.menu.rtp.RtpMenuController;
import art.arcane.wormholes.menu.rules.RulesMenuController;
import art.arcane.wormholes.menu.settings.SettingsMenuController;
import art.arcane.wormholes.menu.transit.TransitMenuController;
import art.arcane.wormholes.nexus.NexusSubsystem;
import art.arcane.wormholes.nexus.ReciprocalLinks;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.util.J;

public final class BukkitMenuHost implements MenuHost {
    private static final int TICK_INTERVAL = 1;

    private final Wormholes plugin;
    private final BooleanSupplier nativeScreens;
    private final BukkitMenuTransport transport;
    private final BukkitMenuCaptures captures;
    private final BukkitPortalMenuHost portals;
    private final BukkitLanguageMenuHost languages;
    private final BukkitChestRenderer chestRenderer;
    private final MenuService service;
    private int tickTask = -1;

    public BukkitMenuHost(Wormholes plugin, PortalManager portalManager, BooleanSupplier nativeScreens) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.nativeScreens = Objects.requireNonNull(nativeScreens, "nativeScreens");
        this.captures = new BukkitMenuCaptures();
        this.portals = new BukkitPortalMenuHost(portalManager);
        this.chestRenderer = new BukkitChestRenderer(plugin, new BukkitMenuIcons(plugin.getLogger(), portals::template, Material::isItem), captures);
        this.transport = new BukkitMenuTransport(plugin, chestRenderer);
        this.languages = new BukkitLanguageMenuHost(new BukkitLanguageMenuHost.Options(plugin.getLanguageService(), plugin.getLanguageEditor(),
            player -> plugin.getLanguageSwitcher().command(player, new String[]{"server"}), plugin.getLogger()));
        BukkitExtensionsMenuHost extensions = new BukkitExtensionsMenuHost();
        List<MenuController> controllers = new ArrayList<>(16);
        controllers.add(new PortalMenuController(portals, new BukkitGatewayMenuHost(portalManager)));
        controllers.add(new SettingsMenuController(portals, extensions));
        controllers.add(new CosmeticsMenuController(portals, new BukkitCosmeticsMenuHost()));
        controllers.add(new CostMenuController(portals, new BukkitCostMenuHost(portalManager)));
        controllers.add(new DestinationMenuController(portals, new BukkitDestinationMenuHost(portalManager, BukkitMenuHost::reciprocalLinks)));
        controllers.add(new ExtensionsMenuController(portals, extensions));
        controllers.add(new RtpMenuController(portals, new BukkitRtpMenuHost(portalManager, plugin.getLogger())));
        controllers.add(new TransitMenuController(portals, new BukkitTransitMenuHost(portalManager)));
        controllers.add(new FidelityMenuController(portals, new BukkitFidelityMenuHost(portalManager)));
        controllers.add(new RulesMenuController(portals, new BukkitRulesMenuHost(portalManager, plugin.getDataFolder().toPath())));
        controllers.add(new LanguageMenuController(languages));
        controllers.add(new NetworkMenuController(new BukkitNetworkMenuHost(plugin.getLogger(), portalManager, NexusSubsystem::active), new Random()));
        controllers.add(new DialMenuController(new BukkitDialMenuHost(portalManager, NexusSubsystem::active)));
        controllers.add(new AtlasMenuController(new BukkitAtlasMenuHost(portalManager, BukkitMenuHost::atlasService)));
        controllers.add(new AccessMenuController(new BukkitAccessMenuHost(portalManager, plugin.getServer())));
        controllers.add(new DoorAccessMenuController(new BukkitDoorAccessMenuHost(plugin, () -> Wormholes.dimensionalDoorManager)));
        this.service = new MenuService(this, new MenuRegistry(controllers));
    }

    public MenuService start() {
        transport.start(service);
        chestRenderer.start(service);
        plugin.getServer().getPluginManager().registerEvents(captures, plugin);
        tickTask = J.sr(service::tick, TICK_INTERVAL);
        return service;
    }

    public void stop() {
        if (tickTask >= 0) {
            J.csr(tickTask);
            tickTask = -1;
        }
        service.shutdown();
        chestRenderer.closeAll();
        transport.stop();
        HandlerList.unregisterAll(captures);
    }

    public boolean openLanguageEditor(Player player, String locale) {
        UUID viewerId = player.getUniqueId();
        if (!nativeViewer(viewerId) || !player.hasPermission(LanguageMenuController.VOLMIT_ADMIN_PERMISSION)
            && !player.hasPermission(LanguageMenuController.ADMIN_PERMISSION)) {
            return false;
        }
        if (locale == null) {
            service.open(viewerId, LanguageRoutes.locales());
            return true;
        }
        BukkitMenuViewer viewer = new BukkitMenuViewer(player);
        chestRenderer.notice(viewer, LanguageMenuController.loadingNotice(viewer, locale));
        languages.load(locale).whenComplete((document, failure) -> {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            if (cause != null) {
                plugin.getLogger().log(Level.SEVERE, "Unable to load Wormholes language messages for " + locale, cause);
            }
            runForViewer(viewerId, () -> languageLoaded(viewer, document, cause));
        });
        return true;
    }

    @Override
    public Optional<MenuViewer> viewer(UUID viewerId) {
        Player player = plugin.getServer().getPlayer(viewerId);
        if (player == null || !player.isOnline()) {
            return Optional.empty();
        }
        return Optional.of(new BukkitMenuViewer(player));
    }

    @Override
    public boolean runForViewer(UUID viewerId, Runnable task) {
        Player player = plugin.getServer().getPlayer(viewerId);
        return player != null && FoliaScheduler.runEntity(plugin, player, task, 0L, null);
    }

    @Override
    public MenuRenderer chestRenderer() {
        return chestRenderer;
    }

    @Override
    public MenuTransport transport() {
        return transport;
    }

    @Override
    public MenuCaptures captures() {
        return captures;
    }

    @Override
    public boolean nativeScreensEnabled() {
        return nativeScreens.getAsBoolean();
    }

    @Override
    public MenuServerInfo serverInfo() {
        return new MenuServerInfo(WormholesBuild.version(), plugin.getServer().getName() + " " + WormholesPlatform.minecraftVersion(),
            nativeScreensEnabled());
    }

    @Override
    public void warn(String message) {
        plugin.getLogger().warning(message);
    }

    private boolean nativeViewer(UUID viewerId) {
        return MenuRendererChoice.choose(service.capabilities().capability(viewerId), nativeScreensEnabled()) == MenuRendererChoice.NATIVE;
    }

    private void languageLoaded(BukkitMenuViewer viewer, LanguageMenuView document, Throwable cause) {
        if (cause == null) {
            service.open(viewer.id(), LanguageRoutes.groups(document.locale()));
            return;
        }
        chestRenderer.notice(viewer, LanguageMenuController.unableToEditNotice(viewer, cause));
        service.open(viewer.id(), LanguageRoutes.locales());
    }

    private static ReciprocalLinks reciprocalLinks() {
        NexusSubsystem nexus = NexusSubsystem.active();
        return nexus == null ? null : nexus.reciprocalLinks();
    }

    private static AtlasService atlasService() {
        NexusSubsystem nexus = NexusSubsystem.active();
        AtlasRuntime atlas = nexus == null ? null : nexus.atlas();
        return atlas == null ? null : atlas.service();
    }
}
