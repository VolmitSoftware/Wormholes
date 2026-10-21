package art.arcane.automator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.math.Transformation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;

final class DisplayStates {
    private DisplayStates() {
    }

    static JsonArray capture(Minecraft client) {
        JsonArray result = new JsonArray();
        if (client.level == null) {
            return result;
        }
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof Display display)) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", display.getId());
            entry.addProperty("uuid", display.getUUID().toString());
            entry.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(display.getType()).toString());
            entry.addProperty("ticks", display.tickCount);
            entry.addProperty("x", display.getX());
            entry.addProperty("y", display.getY());
            entry.addProperty("z", display.getZ());
            float progress = display.calculateInterpolationProgress(0.5F);
            entry.addProperty("progress", progress);
            Display.RenderState state = display.renderState();
            if (state != null) {
                Transformation transformation = state.transformation().get(progress);
                entry.add("leftRotation", quaternion(transformation.leftRotation()));
                entry.add("rightRotation", quaternion(transformation.rightRotation()));
                entry.add("translation", vector(transformation.translation()));
                entry.add("scale", vector(transformation.scale()));
            }
            result.add(entry);
        }
        return result;
    }

    private static JsonArray quaternion(Quaternionfc value) {
        JsonArray result = new JsonArray();
        result.add(value.x());
        result.add(value.y());
        result.add(value.z());
        result.add(value.w());
        return result;
    }

    private static JsonArray vector(Vector3fc value) {
        JsonArray result = new JsonArray();
        result.add(value.x());
        result.add(value.y());
        result.add(value.z());
        return result;
    }
}
