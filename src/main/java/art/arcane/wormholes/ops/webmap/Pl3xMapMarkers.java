package art.arcane.wormholes.ops.webmap;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Pl3xMap markers through {@code net.pl3x.map.core}. A simple layer per world holds the portal icons;
 * the layer is rebuilt from the adapter's own marker cache on every publish.
 */
public final class Pl3xMapMarkers extends ReflectiveMapAdapter {
    private static final String API = "net.pl3x.map.core.Pl3xMap";
    private static final String SIMPLE_LAYER = "net.pl3x.map.core.markers.layer.SimpleLayer";
    private static final String MARKER = "net.pl3x.map.core.markers.marker.Marker";
    private static final String POINT = "net.pl3x.map.core.markers.Point";

    private final Map<UUID, MarkerSnapshot> markers = new LinkedHashMap<>();

    public Pl3xMapMarkers() {
        super("Pl3xMap");
    }

    @Override
    public String id() {
        return "pl3xmap";
    }

    @Override
    boolean classesPresent() {
        return type(API) != null && type(SIMPLE_LAYER) != null && type(MARKER) != null && type(POINT) != null;
    }

    @Override
    void draw(Collection<MarkerSnapshot> changed, Collection<UUID> removed, boolean linkLines)
        throws ReflectiveOperationException {
        for (UUID id : removed) {
            markers.remove(id);
        }
        for (MarkerSnapshot marker : changed) {
            markers.put(marker.id(), marker);
        }
        Object api = type(API).getMethod("api").invoke(null);
        Object worlds = api.getClass().getMethod("getWorldRegistry").invoke(api);
        Method worldGet = worlds.getClass().getMethod("get", String.class);
        Method point = type(POINT).getMethod("of", double.class, double.class);
        Method icon = type(MARKER).getMethod("icon", String.class, type(POINT), String.class, int.class);

        for (String world : worldNames()) {
            Object mapWorld = worldGet.invoke(worlds, world);
            if (mapWorld == null) {
                continue;
            }
            Object layers = mapWorld.getClass().getMethod("getLayerRegistry").invoke(mapWorld);
            Object layer = layers.getClass().getMethod("get", String.class).invoke(layers, MARKER_SET_ID);
            if (layer == null) {
                layer = type(SIMPLE_LAYER).getConstructor(String.class, java.util.function.Supplier.class)
                    .newInstance(MARKER_SET_ID, (java.util.function.Supplier<String>) () -> MARKER_SET_LABEL);
                layers.getClass().getMethod("register", type("net.pl3x.map.core.markers.layer.Layer"))
                    .invoke(layers, layer);
            }
            layer.getClass().getMethod("clearMarkers").invoke(layer);
            for (MarkerSnapshot marker : markers.values()) {
                if (!marker.world().equals(world)) {
                    continue;
                }
                Object position = point.invoke(null, Double.valueOf(marker.x()), Double.valueOf(marker.z()));
                Object built = icon.invoke(null, marker.id().toString(), position, MARKER_SET_ID, Integer.valueOf(16));
                layer.getClass().getMethod("addMarker", type(MARKER)).invoke(layer, built);
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
