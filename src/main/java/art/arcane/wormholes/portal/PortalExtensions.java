package art.arcane.wormholes.portal;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.hook.PortalExtension;
import art.arcane.wormholes.hook.PortalExtensionFactory;
import art.arcane.wormholes.hook.WormholesHooks;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Lane-owned per-portal state attached to one {@link LocalPortal}. */
public final class PortalExtensions {
    private static final Logger LOG = Logger.getLogger("Wormholes");

    private final Map<Class<? extends PortalExtension>, PortalExtension> byType = new LinkedHashMap<>();

    PortalExtensions(LocalPortal portal) {
        List<PortalExtensionFactory> factories = WormholesHooks.portalExtensionFactories();
        for (PortalExtensionFactory factory : factories) {
            try {
                PortalExtension extension = factory.create(portal);
                if (extension != null) {
                    byType.put(factory.type(), extension);
                }
            } catch (RuntimeException failure) {
                LOG.log(Level.WARNING, "portal extension " + factory.type().getSimpleName() + " failed for " + portal.getId(), failure);
            }
        }
    }

    public <T extends PortalExtension> T get(Class<T> type) {
        return type.cast(byType.get(type));
    }

    public Iterable<PortalExtension> all() {
        return byType.values();
    }

    void save(JSONObject portalJson) {
        for (PortalExtension extension : byType.values()) {
            extension.save(portalJson);
        }
    }

    void load(JSONObject portalJson) {
        for (PortalExtension extension : byType.values()) {
            extension.load(portalJson);
        }
    }

    public void collectSync(Map<String, String> settings) {
        for (PortalExtension extension : byType.values()) {
            extension.collectSync(settings);
        }
    }

    public void applySync(Map<String, String> settings) {
        for (PortalExtension extension : byType.values()) {
            String prefix = extension.key() + ".";
            Map<String, String> scoped = new HashMap<>();
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    scoped.put(entry.getKey(), entry.getValue());
                }
            }
            if (!scoped.isEmpty()) {
                extension.applySync(scoped);
            }
        }
    }

    void onPortalDestroyed() {
        for (PortalExtension extension : byType.values()) {
            extension.onPortalDestroyed();
        }
    }
}
