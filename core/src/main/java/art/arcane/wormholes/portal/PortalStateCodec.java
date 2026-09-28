package art.arcane.wormholes.portal;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PortalStateCodec {
    private PortalStateCodec() {
    }

    public static Path file(Path root, UUID id) {
        String[] segments = id.toString().split("-");
        return root.resolve(segments[1]).resolve(segments[0]).resolve(id + ".json");
    }

    public static void write(Map<String, Object> target, Portal.State state) {
        target.put("direction", state.frame().getNormal().name());
        target.put("frame", Map.of("normal", state.frame().getNormal().name(),
            "right", state.frame().getRight().name(), "up", state.frame().getUp().name()));
        target.put("id", state.id().toString());
        target.put("origin", vector(state.origin()));
        target.put("name", state.name());
    }

    public static Portal.State read(Map<String, Object> source) {
        Direction direction = Direction.valueOf(string(source, "direction"));
        boolean explicitFrame = source.containsKey("frame");
        PortalFrame frame = PortalFrame.canonical(direction);
        if (explicitFrame) {
            Map<String, Object> stored = object(source, "frame");
            frame = new PortalFrame(Direction.valueOf((String) stored.getOrDefault("normal", direction.name())),
                Direction.valueOf(string(stored, "right")), Direction.valueOf(string(stored, "up")));
        }
        return new Portal.State(UUID.fromString(string(source, "id")), readVector(object(source, "origin")),
            string(source, "name"), frame, explicitFrame);
    }

    public static Map<String, Object> writeGeometry(String worldKey, PortalGeometry geometry) {
        AxisAlignedBB area = geometry.getArea();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("worldKey", worldKey);
        result.put("area", Map.of("xa", area.getXa(), "xb", area.getXb(), "ya", area.getYa(),
            "yb", area.getYb(), "za", area.getZa(), "zb", area.getZb()));
        List<Map<String, Object>> cells = new ArrayList<>();
        for (GeometryVector cell : geometry.getBlockPositions()) {
            cells.add(Map.of("x", cell.getBlockX(), "y", cell.getBlockY(), "z", cell.getBlockZ()));
        }
        result.put("blocks", cells);
        return result;
    }

    public static void readGeometry(Map<String, Object> source, PortalGeometry geometry) {
        Map<String, Object> bounds = object(source, "area");
        AxisAlignedBB area = new AxisAlignedBB(number(bounds, "xa"), number(bounds, "xb"),
            number(bounds, "ya"), number(bounds, "yb"), number(bounds, "za"), number(bounds, "zb"));
        if (!source.containsKey("blocks")) {
            geometry.setArea(area);
            return;
        }
        if (!(source.get("blocks") instanceof List<?> blocks)) {
            throw new IllegalArgumentException("Portal blocks must be an array");
        }
        List<GeometryVector> cells = new ArrayList<>(blocks.size());
        for (Object block : blocks) {
            cells.add(readVector(asObject(block)));
        }
        geometry.restore(area, cells);
    }

    public static Map<String, Object> object(Map<String, Object> source, String key) {
        return asObject(source.get(key));
    }

    public static String string(Map<String, Object> source, String key) {
        if (!(source.get(key) instanceof String value)) {
            throw new IllegalArgumentException("Missing portal string: " + key);
        }
        return value;
    }

    private static Map<String, Object> vector(GeometryVector vector) {
        return Map.of("x", vector.x(), "y", vector.y(), "z", vector.z());
    }

    private static GeometryVector readVector(Map<String, Object> source) {
        return new GeometryVector(number(source, "x"), number(source, "y"), number(source, "z"));
    }

    private static double number(Map<String, Object> source, String key) {
        if (!(source.get(key) instanceof Number value) || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException("Missing or invalid portal number: " + key);
        }
        return value.doubleValue();
    }

    private static Map<String, Object> asObject(Object source) {
        if (!(source instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Portal object expected");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Portal field must be a string");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }
}
