package art.arcane.wormholes.render;

import art.arcane.optics.stream.BiomeVisual;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Locale;

public final class BiomeVisuals {
    private BiomeVisuals() {
    }

    public static BiomeVisual read(JsonObject biome) {
        JsonObject effects = biome.getAsJsonObject("effects");
        return new BiomeVisual(biome.get("temperature").getAsFloat(), biome.get("downfall").getAsFloat(),
            biome.get("has_precipitation").getAsBoolean(), BiomeVisual.TemperatureModifier.valueOf(
                biome.has("temperature_modifier") ? biome.get("temperature_modifier").getAsString().toUpperCase(Locale.ROOT) : "NONE"),
            rgb(effects.getAsJsonPrimitive("water_color")), color(effects, "foliage_color"), color(effects, "dry_foliage_color"),
            color(effects, "grass_color"), BiomeVisual.GrassModifier.valueOf(effects.has("grass_color_modifier")
                ? effects.get("grass_color_modifier").getAsString().toUpperCase(Locale.ROOT) : "NONE"));
    }

    private static int color(JsonObject effects, String key) {
        return effects.has(key) ? rgb(effects.getAsJsonPrimitive(key)) : -1;
    }

    private static int rgb(JsonPrimitive value) {
        if (value.isNumber()) {
            return value.getAsInt();
        }
        String encoded = value.getAsString();
        if (encoded.length() != 7 || encoded.charAt(0) != '#') {
            throw new IllegalArgumentException("Invalid biome color " + encoded);
        }
        return Integer.parseInt(encoded.substring(1), 16);
    }
}
