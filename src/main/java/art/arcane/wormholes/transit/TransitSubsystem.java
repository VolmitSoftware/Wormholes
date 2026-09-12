package art.arcane.wormholes.transit;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.hook.WormholesSubsystem;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.network.convoy.ConvoyArrivalPlacer;
import art.arcane.wormholes.network.convoy.ConvoyHandlers;
import art.arcane.wormholes.network.convoy.ConvoyLedger;
import art.arcane.wormholes.network.convoy.ConvoyTransferService;

/** Lifecycle entry point for the transit lane. Holds the live {@code [transit]} snapshot for the hot paths. */
public final class TransitSubsystem implements WormholesSubsystem {
    public static final String CONVOY_FOLDER = "convoy";

    private static volatile TransitConfig active = new TransitConfig();

    private final Cinematics cinematics = new Cinematics();

    /** The current {@code [transit]} section; defaults until the subsystem starts. */
    public static TransitConfig config() {
        return active;
    }

    static void apply(TransitConfig config) {
        active = config == null ? new TransitConfig() : config;
    }

    static long convoyTimeoutMillis() {
        return TimeUnit.SECONDS.toMillis(Math.max(1, config().convoyCrossServerTimeoutSec));
    }

    @Override
    public String id() {
        return "transit";
    }

    @Override
    public void register(WormholesRegistrar registrar) {
        registrar.portalExtension(new TransitExtensionFactory());
        registrar.traversalGate(new TransitGate());
        registrar.traversalObserver(new TransitObserver());
        registrar.portalMenuEntry(new TransitMenuEntry());
        ConvoyHandlers.register(registrar, TransitSubsystem::convoyTransfers, TransitSubsystem::convoyArrivals,
            TransitSubsystem::convoyTimeoutMillis);
    }

    @Override
    public void start(Wormholes plugin) {
        WormholesSettings settings = Wormholes.settings;
        apply(settings == null ? null : settings.getTransit());
        cinematics.start(plugin);
        TraversalService service = Wormholes.traversalService;
        if (service == null) {
            return;
        }
        service.installConvoyArrivalHook(service.convoyArrivals());
        ConvoyJournal journal = new ConvoyJournal(plugin.getDataFolder().toPath().resolve(CONVOY_FOLDER));
        restoreStaleConvoys(plugin, service, journal);
        service.convoyTransfers().journal(journal);
    }

    @Override
    public void stop() {
        cinematics.stop();
        TraversalService service = Wormholes.traversalService;
        if (service != null) {
            service.installConvoyArrivalHook(null);
        }
        apply(null);
    }

    @Override
    public void onSettingsReloaded(WormholesSettings settings) {
        apply(settings == null ? null : settings.getTransit());
    }

    private static ConvoyTransferService convoyTransfers() {
        TraversalService service = Wormholes.traversalService;
        return service == null ? null : service.convoyTransfers();
    }

    private static ConvoyArrivalPlacer convoyArrivals() {
        TraversalService service = Wormholes.traversalService;
        return service == null ? null : service.convoyArrivals();
    }

    /** Frozen members of groups older than the transfer window are released; unloaded ones recover on chunk load. */
    private static void restoreStaleConvoys(Wormholes plugin, TraversalService service, ConvoyJournal journal) {
        List<ConvoyLedger.Group> stale = ConvoyJournal.stale(journal.load(), System.currentTimeMillis(), convoyTimeoutMillis());
        int restored = 0;
        if (!stale.isEmpty() && !FoliaScheduler.isFoliaThreading(plugin.getServer())) {
            for (ConvoyLedger.Group group : stale) {
                for (UUID memberId : group.members()) {
                    Entity entity = Bukkit.getEntity(memberId);
                    if (entity != null && !(entity instanceof Player)) {
                        service.restoreConvoyMember(entity);
                        restored++;
                    }
                }
            }
        }
        journal.clear();
        if (!stale.isEmpty()) {
            Wormholes.i("[convoy] journal: " + stale.size() + " stale group(s), restored " + restored + " loaded member(s)");
        }
    }
}
