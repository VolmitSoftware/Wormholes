package art.arcane.wormholes.util;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

public final class GeometryPersistence {
    private GeometryPersistence() {
    }

    public static JSONObject frame(Frame frame) {
        JSONObject json = new JSONObject();
        json.put("normal", frame.getNormal().name());
        json.put("right", frame.getRight().name());
        json.put("up", frame.getUp().name());
        return json;
    }

    public static Frame frame(Face fallbackNormal, JSONObject json) {
        if (json == null) {
            return Frame.canonical(fallbackNormal);
        }
        Face normal = json.has("normal") ? Face.valueOf(json.getString("normal")) : fallbackNormal;
        return new Frame(normal, Face.valueOf(json.getString("right")), Face.valueOf(json.getString("up")));
    }

    public static JSONObject bounds(Box bounds) {
        JSONObject json = new JSONObject();
        json.put("xa", bounds.getXa());
        json.put("xb", bounds.getXb());
        json.put("ya", bounds.getYa());
        json.put("yb", bounds.getYb());
        json.put("za", bounds.getZa());
        json.put("zb", bounds.getZb());
        return json;
    }

    public static Box bounds(JSONObject json) {
        return new Box(json.getDouble("xa"), json.getDouble("xb"), json.getDouble("ya"), json.getDouble("yb"), json.getDouble("za"), json.getDouble("zb"));
    }
}
