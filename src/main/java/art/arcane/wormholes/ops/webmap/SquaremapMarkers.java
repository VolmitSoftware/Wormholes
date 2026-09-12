package art.arcane.wormholes.ops.webmap;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * squaremap markers through {@code xyz.jpenilla.squaremap.api}. One simple layer provider per world
 * holds the portal icons, rebuilt from the adapter's cache on every publish.
 */
public final class SquaremapMarkers extends ReflectiveMapAdapter {
    private static final String PROVIDER = "xyz.jpenilla.squaremap.api.SquaremapProvider";
    private static final String KEY = "xyz.jpenilla.squaremap.api.Key";
    private static final String POINT = "xyz.jpenilla.squaremap.api.Point";
    private static final String MARKER = "xyz.jpenilla.squaremap.api.marker.Marker";
    private static final String SIMPLE_LAYER_PROVIDER = "xyz.jpenilla.squaremap.api.SimpleLayerProvider";
    private static final String BUKKIT_WORLD_IDENTIFIER = "xyz.jpenilla.squaremap.api.BukkitAdapter";

    private final Map<UUID, MarkerSnapshot> markers = new LinkedHashMap<>();

    public SquaremapMarkers() {
        super("squaremap");
    }

    @Override
    public String id() {
        return "squaremap";
    }

    @Override
    boolean classesPresent() {
        return type(PROVIDER) != null && type(KEY) != null && type(POINT) != null && type(MARKER) != null
            && type(SIMPLE_LAYER_PROVIDER) != null && type(BUKKIT_WORLD_IDENTIFIER) != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    void draw(Collection<MarkerSnapshot> changed, Collection<UUID> removed, boolean linkLines)
        throws ReflectiveOperationException {
        for (UUID id : removed) {
            markers.remove(id);
        }
        for (MarkerSnapshot marker : changed) {
            markers.put(marker.id(), marker);
        }
        Object api = type(PROVIDER).getMethod("get").invoke(null);
        Method key = type(KEY).getMethod("of", String.class);
        Method point = type(POINT).getMethod("of", double.class, double.class);
        Method icon = type(MARKER).getMethod("icon", type(POINT), type(KEY), int.class);
        Object layerKey = key.invoke(null, MARKER_SET_ID);

        for (String worldName : worldNames()) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                continue;
            }
            Object identifier = type(BUKKIT_WORLD_IDENTIFIER).getMethod("worldIdentifier", World.class)
                .invoke(null, world);
            Optional<Object> mapWorld = (Optional<Object>) api.getClass()
                .getMethod("getWorldIfEnabled", type("xyz.jpenilla.squaremap.api.WorldIdentifier"))
                .invoke(api, identifier);
            if (mapWorld.isEmpty()) {
                continue;
            }
            Object registry = mapWorld.get().getClass().getMethod("layerRegistry").invoke(mapWorld.get());
            Object provider = registry.getClass().getMethod("get", type(KEY)).invoke(registry, layerKey);
            if (provider == null) {
                Object builder = type(SIMPLE_LAYER_PROVIDER).getMethod("builder", String.class)
                    .invoke(null, MARKER_SET_LABEL);
                provider = builder.getClass().getMethod("build").invoke(builder);
                registry.getClass().getMethod("register", type(KEY), type("xyz.jpenilla.squaremap.api.LayerProvider"))
                    .invoke(registry, layerKey, provider);
            }
            provider.getClass().getMethod("clearMarkers").invoke(provider);
            for (MarkerSnapshot marker : markers.values()) {
                if (!marker.world().equals(worldName)) {
                    continue;
                }
                Object position = point.invoke(null, Double.valueOf(marker.x()), Double.valueOf(marker.z()));
                Object built = icon.invoke(null, position, layerKey, Integer.valueOf(16));
                provider.getClass().getMethod("addMarker", type(KEY), type(MARKER))
                    .invoke(provider, key.invoke(null, marker.id().toString().replace("-", "")), built);
            }
        }
    }

    private Collection<String> worldNames() {
        Map<String, Boolean> names = new LinkedHashMap<>();
        for (MarkerSnapshot marker : markers.values()) {
            names.put(marker.world(), Boolean.TRUE);
        }
        return names.keySet();
    }
}
