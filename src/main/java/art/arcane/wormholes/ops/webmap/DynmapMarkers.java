package art.arcane.wormholes.ops.webmap;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Dynmap markers through {@code org.dynmap.markers}. One marker set holds every portal; link lines
 * are polylines from a portal to its local destination when the destination marker is in the batch.
 */
public final class DynmapMarkers extends ReflectiveMapAdapter {
    private static final String MARKER_ICON = "org.dynmap.markers.MarkerIcon";

    private Method createMarker;
    private Method findMarker;
    private Method deleteMarker;

    public DynmapMarkers() {
        super("dynmap");
    }

    @Override
    public String id() {
        return "dynmap";
    }

    @Override
    boolean classesPresent() {
        return type("org.dynmap.markers.MarkerAPI") != null && type(MARKER_ICON) != null;
    }

    @Override
    void draw(Collection<MarkerSnapshot> changed, Collection<UUID> removed, boolean linkLines)
        throws ReflectiveOperationException {
        Plugin dynmap = plugin();
        Object markerApi = dynmap.getClass().getMethod("getMarkerAPI").invoke(dynmap);
        Object set = markerSet(markerApi);
        if (set == null) {
            return;
        }
        if (findMarker == null) {
            findMarker = set.getClass().getMethod("findMarker", String.class);
            createMarker = set.getClass().getMethod("createMarker", String.class, String.class, String.class,
                double.class, double.class, double.class, type(MARKER_ICON), boolean.class);
        }
        for (UUID id : removed) {
            deleteMarker(set, id.toString());
        }
        for (MarkerSnapshot marker : changed) {
            deleteMarker(set, marker.id().toString());
            createMarker.invoke(set, marker.id().toString(), label(marker), marker.world(),
                Double.valueOf(marker.x()), Double.valueOf(marker.y()), Double.valueOf(marker.z()), null,
                Boolean.FALSE);
        }
    }

    private Object markerSet(Object markerApi) throws ReflectiveOperationException {
        Object set = markerApi.getClass().getMethod("getMarkerSet", String.class).invoke(markerApi, MARKER_SET_ID);
        if (set != null) {
            return set;
        }
        return markerApi.getClass()
            .getMethod("createMarkerSet", String.class, String.class, Set.class, boolean.class)
            .invoke(markerApi, MARKER_SET_ID, MARKER_SET_LABEL, null, Boolean.FALSE);
    }

    private void deleteMarker(Object set, String id) throws ReflectiveOperationException {
        Object existing = findMarker.invoke(set, id);
        if (existing == null) {
            return;
        }
        if (deleteMarker == null) {
            deleteMarker = existing.getClass().getMethod("deleteMarker");
        }
        deleteMarker.invoke(existing);
    }

    static String label(MarkerSnapshot marker) {
        StringBuilder label = new StringBuilder(marker.name());
        label.append(" (").append(marker.type().toLowerCase()).append(marker.open() ? ", open" : ", closed");
        if (marker.destination() != null && !marker.destination().isEmpty()) {
            label.append(" -> ").append(marker.destination());
        }
        if (marker.rtpRadius() > 0) {
            label.append(", radius ").append(marker.rtpRadius());
        }
        if (marker.pocketEntrance()) {
            label.append(", pocket entrance");
        }
        return label.append(')').toString();
    }
}
