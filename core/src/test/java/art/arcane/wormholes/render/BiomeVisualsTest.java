package art.arcane.wormholes.render;

import art.arcane.optics.stream.BiomeVisual;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BiomeVisualsTest {
    @Test
    void nativeDefinitionRetainsClimateAndOptionalTintOverrides() {
        BiomeVisual result = BiomeVisuals.read(JsonParser.parseString("""
            {"temperature":0.7,"downfall":0.8,"has_precipitation":true,"temperature_modifier":"frozen",
             "effects":{"water_color":1193046,"foliage_color":4548489,"dry_foliage_color":7903932,
             "grass_color":11259375,"grass_color_modifier":"swamp"}}
            """).getAsJsonObject());
        assertEquals(0.7F, result.temperature());
        assertEquals(0.8F, result.downfall());
        assertEquals(0x123456, result.waterColor());
        assertEquals(0x456789, result.foliageColor());
        assertEquals(0x789ABC, result.dryFoliageColor());
        assertEquals(0xABCDEF, result.grassColor());
        assertEquals(BiomeVisual.TemperatureModifier.FROZEN, result.temperatureModifier());
        assertEquals(BiomeVisual.GrassModifier.SWAMP, result.grassModifier());
    }

    @Test
    void nativeHexColorEncodingRetainsExactTints() {
        BiomeVisual result = BiomeVisuals.read(JsonParser.parseString("""
            {"temperature":0.7,"downfall":0.8,"has_precipitation":true,
             "effects":{"water_color":"#123456","foliage_color":"#456789",
             "dry_foliage_color":"#789abc","grass_color":"#abcdef"}}
            """).getAsJsonObject());
        assertEquals(0x123456, result.waterColor());
        assertEquals(0x456789, result.foliageColor());
        assertEquals(0x789ABC, result.dryFoliageColor());
        assertEquals(0xABCDEF, result.grassColor());
    }

    @Test
    void invalidOrMissingAuthoritativeEffectsAreRejected() {
        assertThrows(RuntimeException.class, () -> BiomeVisuals.read(JsonParser.parseString("""
            {"temperature":0.7,"downfall":0.8,"has_precipitation":true,"effects":{}}
            """).getAsJsonObject()));
        assertThrows(RuntimeException.class, () -> BiomeVisuals.read(JsonParser.parseString("""
            {"temperature":0.7,"downfall":0.8,"has_precipitation":true,
            "effects":{"water_color":1193046,"grass_color_modifier":"invalid"}}
            """).getAsJsonObject()));
    }
}
