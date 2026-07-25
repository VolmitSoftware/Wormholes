package art.arcane.wormholes.api.traversal.internal;

import art.arcane.wormholes.api.traversal.TraversalCostProvider;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BukkitTraversalCostProviderSource implements Supplier<List<TraversalCostRegistration>> {
    private final Logger logger;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private volatile List<TraversalCostRegistration> cached;

    public BukkitTraversalCostProviderSource(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public List<TraversalCostRegistration> get() {
        List<TraversalCostRegistration> snapshot = cached;

        if (snapshot != null) {
            return snapshot;
        }

        snapshot = rebuild();
        cached = snapshot;
        return snapshot;
    }

    public void invalidate() {
        cached = null;
    }

    private List<TraversalCostRegistration> rebuild() {
        Collection<RegisteredServiceProvider<TraversalCostProvider>> raw =
            Bukkit.getServicesManager().getRegistrations(TraversalCostProvider.class);
        List<TraversalCostRegistration> mapped = new ArrayList<>(raw.size());

        for (RegisteredServiceProvider<TraversalCostProvider> registration : raw) {
            TraversalCostProvider provider = registration.getProvider();
            Plugin owner = registration.getPlugin();

            if (provider == null || owner == null) {
                continue;
            }

            String providerId = resolveProviderId(provider, owner.getName());

            if (providerId == null) {
                continue;
            }

            mapped.add(new TraversalCostRegistration(provider, providerId, owner.getName(), registration.getPriority(),
                owner::isEnabled));
        }

        return TraversalCostRegistrations.order(mapped, this::reportDuplicate);
    }

    String resolveProviderId(TraversalCostProvider provider, String pluginName) {
        try {
            String providerId = provider.providerId();

            if (providerId == null || providerId.isBlank()) {
                logger.warning("Ignoring traversal cost provider from plugin '" + pluginName
                    + "' because providerId() returned a blank value");
                return null;
            }

            String resolved = providerId.strip();
            warnAboutGeneratedId(provider, pluginName, resolved);
            return resolved;
        } catch (Throwable error) {
            logger.log(Level.WARNING, "Ignoring traversal cost provider from plugin '" + pluginName
                + "' because providerId() threw", error);
            return null;
        }
    }

    private void warnAboutGeneratedId(TraversalCostProvider provider, String pluginName, String providerId) {
        Class<?> type = provider.getClass();

        if (!providerId.equals(type.getName()) || !generated(type) || !warned.add(pluginName)) {
            return;
        }

        logger.warning("Traversal cost provider from plugin '" + pluginName + "' did not override providerId(); "
            + "using the generated name '" + providerId + "', which changes on every restart and on unrelated "
            + "edits to that plugin. Override providerId() if this provider moves value");
    }

    private static boolean generated(Class<?> type) {
        return type.isHidden() || type.isSynthetic() || type.isAnonymousClass() || type.isLocalClass();
    }

    private void reportDuplicate(TraversalCostRegistration kept, TraversalCostRegistration dropped) {
        logger.warning("Two traversal cost providers claim the id '" + dropped.providerId() + "'; keeping the one from '"
            + (kept == null ? "unknown" : kept.pluginName()) + "' and ignoring the one from '" + dropped.pluginName()
            + "'");
    }
}
