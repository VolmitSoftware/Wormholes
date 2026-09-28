package art.arcane.wormholes.util;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.portal.PortalFrame;

public final class GeometryPersistence {
    private GeometryPersistence() {
    }

    public static JSONObject frame(PortalFrame frame) {
        JSONObject json = new JSONObject();
        json.put("normal", frame.getNormal().name());
        json.put("right", frame.getRight().name());
        json.put("up", frame.getUp().name());
        return json;
    }

    public static PortalFrame frame(Direction fallbackNormal, JSONObject json) {
        if (json == null) {
            return PortalFrame.canonical(fallbackNormal);
        }
        Direction normal = json.has("normal") ? Direction.valueOf(json.getString("normal")) : fallbackNormal;
        return new PortalFrame(normal, Direction.valueOf(json.getString("right")), Direction.valueOf(json.getString("up")));
    }

    public static JSONObject bounds(AxisAlignedBB bounds) {
        JSONObject json = new JSONObject();
        json.put("xa", bounds.getXa());
        json.put("xb", bounds.getXb());
        json.put("ya", bounds.getYa());
        json.put("yb", bounds.getYb());
        json.put("za", bounds.getZa());
        json.put("zb", bounds.getZb());
        return json;
    }

    public static AxisAlignedBB bounds(JSONObject json) {
        return new AxisAlignedBB(json.getDouble("xa"), json.getDouble("xb"), json.getDouble("ya"), json.getDouble("yb"), json.getDouble("za"), json.getDouble("zb"));
    }
}
