package art.arcane.wormholes.render;

import java.util.Map;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.hook.PortalExtension;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.atmosphere.AtmosphereMode;
import art.arcane.wormholes.render.lod.LodProfile;

/**
 * Per-portal fidelity overrides. Every field is optional: an unset field inherits the matching global
 * default from {@link FidelitySettings}, is not written to the portal JSON and is not replicated.
 */
public final class FidelityPortalExtension implements PortalExtension {
    public static final String KEY = "fidelity";
    static final String ATMOSPHERE = KEY + ".atmosphere";
    static final String ACOUSTICS = KEY + ".acoustics";
    static final String LOD = KEY + ".lod";
    static final String BLOCK_ENTITIES = KEY + ".block_entities";

    private volatile AtmosphereMode atmosphereMode;
    private volatile AcousticsProfile acousticsProfile;
    private volatile LodProfile lodProfile;
    private volatile Boolean blockEntities;

    @Override
    public String key() {
        return KEY;
    }

    public AtmosphereMode atmosphereMode() {
        return atmosphereMode;
    }

    public AcousticsProfile acousticsProfile() {
        return acousticsProfile;
    }

    public LodProfile lodProfile() {
        return lodProfile;
    }

    public Boolean blockEntities() {
        return blockEntities;
    }

    public AtmosphereMode effectiveAtmosphereMode() {
        AtmosphereMode mode = atmosphereMode;
        return mode == null ? FidelitySettings.atmosphereModeDefault : mode;
    }

    public AcousticsProfile effectiveAcousticsProfile() {
        AcousticsProfile profile = acousticsProfile;
        return profile == null ? FidelitySettings.acousticsProfileDefault : profile;
    }

    public LodProfile effectiveLodProfile() {
        LodProfile profile = lodProfile;
        return profile == null ? LodProfile.BALANCED : profile;
    }

    public boolean effectiveBlockEntities() {
        Boolean enabled = blockEntities;
        return enabled == null ? FidelitySettings.blockEntities : enabled.booleanValue();
    }

    public void setAtmosphereMode(AtmosphereMode mode) {
        atmosphereMode = mode;
    }

    public void setAcousticsProfile(AcousticsProfile profile) {
        acousticsProfile = profile;
    }

    public void setLodProfile(LodProfile profile) {
        lodProfile = profile;
    }

    public void setBlockEntities(Boolean enabled) {
        blockEntities = enabled;
    }

    @Override
    public void save(JSONObject portalJson) {
        AtmosphereMode mode = atmosphereMode;
        if (mode != null) {
            portalJson.put(ATMOSPHERE, mode.configName());
        }
        AcousticsProfile acoustics = acousticsProfile;
        if (acoustics != null) {
            portalJson.put(ACOUSTICS, acoustics.configName());
        }
        LodProfile lod = lodProfile;
        if (lod != null) {
            portalJson.put(LOD, lod.configName());
        }
        Boolean entities = blockEntities;
        if (entities != null) {
            portalJson.put(BLOCK_ENTITIES, entities.booleanValue());
        }
    }

    @Override
    public void load(JSONObject portalJson) {
        atmosphereMode = AtmosphereMode.parse(portalJson.optString(ATMOSPHERE, ""), null);
        acousticsProfile = AcousticsProfile.parse(portalJson.optString(ACOUSTICS, ""), null);
        lodProfile = LodProfile.parse(portalJson.optString(LOD, ""), null);
        blockEntities = portalJson.has(BLOCK_ENTITIES) ? Boolean.valueOf(portalJson.optBoolean(BLOCK_ENTITIES, true)) : null;
    }

    @Override
    public void collectSync(Map<String, String> settings) {
        AtmosphereMode mode = atmosphereMode;
        if (mode != null) {
            settings.put(ATMOSPHERE, mode.configName());
        }
        AcousticsProfile acoustics = acousticsProfile;
        if (acoustics != null) {
            settings.put(ACOUSTICS, acoustics.configName());
        }
        LodProfile lod = lodProfile;
        if (lod != null) {
            settings.put(LOD, lod.configName());
        }
        Boolean entities = blockEntities;
        if (entities != null) {
            settings.put(BLOCK_ENTITIES, entities.toString());
        }
    }

    @Override
    public void applySync(Map<String, String> settings) {
        atmosphereMode = AtmosphereMode.parse(settings.get(ATMOSPHERE), null);
        acousticsProfile = AcousticsProfile.parse(settings.get(ACOUSTICS), null);
        lodProfile = LodProfile.parse(settings.get(LOD), null);
        String entities = settings.get(BLOCK_ENTITIES);
        blockEntities = entities == null ? null : Boolean.valueOf(Boolean.parseBoolean(entities));
    }
}
