package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.atmosphere.AtmosphereMode;
import art.arcane.wormholes.render.lod.LodProfile;

final class FidelityPortalExtensionTest {
    @Test
    void unsetFieldsInheritTheGlobalDefaultsAndPersistNothing() {
        FidelitySettings.atmosphereModeDefault = AtmosphereMode.TINT_LIGHT;
        FidelitySettings.acousticsProfileDefault = AcousticsProfile.AMBIENT;
        FidelitySettings.blockEntities = true;
        FidelityPortalExtension extension = new FidelityPortalExtension();

        assertEquals("fidelity", extension.key());
        assertNull(extension.atmosphereMode());
        assertEquals(AtmosphereMode.TINT_LIGHT, extension.effectiveAtmosphereMode());
        assertEquals(AcousticsProfile.AMBIENT, extension.effectiveAcousticsProfile());
        assertEquals(LodProfile.BALANCED, extension.effectiveLodProfile());
        assertTrue(extension.effectiveBlockEntities());

        JSONObject json = new JSONObject();
        extension.save(json);
        assertFalse(json.has("fidelity.atmosphere"));
        assertFalse(json.has("fidelity.block_entities"));
        Map<String, String> sync = new LinkedHashMap<String, String>();
        extension.collectSync(sync);
        assertTrue(sync.isEmpty());
    }

    @Test
    void explicitValuesRoundTripThroughJsonAndTheSyncBag() {
        FidelityPortalExtension source = new FidelityPortalExtension();
        source.setAtmosphereMode(AtmosphereMode.FULL);
        source.setAcousticsProfile(AcousticsProfile.OFF);
        source.setLodProfile(LodProfile.FAR);
        source.setBlockEntities(Boolean.FALSE);

        JSONObject json = new JSONObject();
        source.save(json);
        assertEquals("full", json.getString("fidelity.atmosphere"));
        assertEquals("off", json.getString("fidelity.acoustics"));
        assertEquals("far", json.getString("fidelity.lod"));
        assertFalse(json.getBoolean("fidelity.block_entities"));

        FidelityPortalExtension loaded = new FidelityPortalExtension();
        loaded.load(json);
        assertEquals(AtmosphereMode.FULL, loaded.atmosphereMode());
        assertEquals(AcousticsProfile.OFF, loaded.acousticsProfile());
        assertEquals(LodProfile.FAR, loaded.lodProfile());
        assertEquals(Boolean.FALSE, loaded.blockEntities());
        assertFalse(loaded.effectiveBlockEntities());

        Map<String, String> sync = new LinkedHashMap<String, String>();
        source.collectSync(sync);
        assertEquals(4, sync.size());
        assertEquals("full", sync.get("fidelity.atmosphere"));
        assertEquals("false", sync.get("fidelity.block_entities"));

        FidelityPortalExtension mirrored = new FidelityPortalExtension();
        mirrored.applySync(sync);
        assertEquals(AtmosphereMode.FULL, mirrored.atmosphereMode());
        assertEquals(AcousticsProfile.OFF, mirrored.acousticsProfile());
        assertEquals(LodProfile.FAR, mirrored.lodProfile());
        assertEquals(Boolean.FALSE, mirrored.blockEntities());
    }

    @Test
    void unknownStoredNamesFallBackToInherit() {
        JSONObject json = new JSONObject();
        json.put("fidelity.atmosphere", "sparkle");
        json.put("fidelity.lod", "");
        FidelityPortalExtension loaded = new FidelityPortalExtension();
        loaded.load(json);
        assertNull(loaded.atmosphereMode());
        assertNull(loaded.lodProfile());
    }

    @Test
    void factoryCreatesTheExtensionType() {
        FidelityExtensionFactory factory = new FidelityExtensionFactory();
        assertSame(FidelityPortalExtension.class, factory.type());
        assertTrue(factory.create(null) instanceof FidelityPortalExtension);
    }

    @Test
    void enumsCycleAndParseByLowercaseName() {
        assertEquals(AtmosphereMode.TINT, AtmosphereMode.OFF.next());
        assertEquals(AtmosphereMode.OFF, AtmosphereMode.FULL.next());
        assertEquals(AtmosphereMode.TINT_LIGHT, AtmosphereMode.parse("tint_light", AtmosphereMode.OFF));
        assertEquals(AtmosphereMode.OFF, AtmosphereMode.parse("nope", AtmosphereMode.OFF));
        assertEquals("tint_light", AtmosphereMode.TINT_LIGHT.configName());
        assertEquals(AcousticsProfile.AMBIENT_EVENTS, AcousticsProfile.AMBIENT.next());
        assertEquals(LodProfile.NEAR, LodProfile.FAR.next());
        assertTrue(AtmosphereMode.TINT_LIGHT.promotesSkyLight());
        assertFalse(AtmosphereMode.TINT.promotesSkyLight());
        assertTrue(AtmosphereMode.FULL.usesFogPlate());
    }
}
