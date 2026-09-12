package art.arcane.wormholes.ops.webmap;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * BlueMap markers through {@code de.bluecolored.bluemap.api}. Portals become POI markers in a
 * Wormholes marker set on every map whose world matches the portal's world.
 */
public final class BlueMapMarkers extends ReflectiveMapAdapter {
    private static final String API = "de.bluecolored.bluemap.api.BlueMapAPI";
    private static final String MARKER_SET = "de.bluecolored.bluemap.api.markers.MarkerSet";
    private static final String POI_MARKER = "de.bluecolored.bluemap.api.markers.POIMarker";

    public BlueMapMarkers() {
        super("BlueMap");
    }

    @Override
    public String id() {
        return "bluemap";
    }

    @Override
    boolean classesPresent() {
        return type(API) != null && type(MARKER_SET) != null && type(POI_MARKER) != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    void draw(Collection<MarkerSnapshot> changed, Collection<UUID> removed, boolean linkLines)
        throws ReflectiveOperationException {
        Class<?> apiType = type(API);
        Object api = ((Optional<Object>) apiType.getMethod("getInstance").invoke(null)).orElse(null);
        if (api == null) {
            return;
        }
        for (Object map : (Collection<Object>) apiType.getMethod("getMaps").invoke(api)) {
            Object world = map.getClass().getMethod("getWorld").invoke(map);
            String worldId = String.valueOf(world.getClass().getMethod("getId").invoke(world));
            Map<String, Object> markerSets =
                (Map<String, Object>) map.getClass().getMethod("getMarkerSets").invoke(map);
            Object set = markerSets.computeIfAbsent(MARKER_SET_ID, key -> newMarkerSet());
            if (set == null) {
                continue;
            }
            Map<String, Object> markers = (Map<String, Object>) set.getClass().getMethod("getMarkers").invoke(set);
            for (UUID id : removed) {
                markers.remove(id.toString());
            }
            for (MarkerSnapshot marker : changed) {
                if (!worldId.endsWith(marker.world())) {
                    markers.remove(marker.id().toString());
                    continue;
                }
                markers.put(marker.id().toString(), poi(marker));
            }
        }
    }

    private Object newMarkerSet() {
        try {
            Object builder = type(MARKER_SET).getMethod("builder").invoke(null);
            Method label = builder.getClass().getMethod("label", String.class);
            Object labelled = label.invoke(builder, MARKER_SET_LABEL);
            return labelled.getClass().getMethod("build").invoke(labelled);
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    private Object poi(MarkerSnapshot marker) {
        try {
            Object builder = type(POI_MARKER).getMethod("builder").invoke(null);
            Object labelled = builder.getClass().getMethod("label", String.class)
                .invoke(builder, DynmapMarkers.label(marker));
            Object positioned = labelled.getClass()
                .getMethod("position", double.class, double.class, double.class)
                .invoke(labelled, Double.valueOf(marker.x()), Double.valueOf(marker.y()), Double.valueOf(marker.z()));
            return positioned.getClass().getMethod("build").invoke(positioned);
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }
}
