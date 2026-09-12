package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.hook.PortalExtension;
import art.arcane.wormholes.portal.LocalPortal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The rules document, charge pool and compiled chain of one portal. The document is server policy and is
 * never replicated; only the cooldown group and the numeric profile ride the settings bag so a linked
 * portal on another server can show the same route card.
 */
public final class RulesPortalExtension implements PortalExtension {
    public static final String KEY = "rules";
    private static final String DOCUMENT_KEY = KEY + ".document";
    private static final String CHARGES_KEY = KEY + ".charges";
    private static final String SYNC_COOLDOWN_GROUP = KEY + ".cooldownGroup";
    private static final String SYNC_PROFILE = KEY + ".profile";

    private final LocalPortal portal;
    private final ChargePool charges = new ChargePool();
    private volatile RuleDocument document = RuleDocument.EMPTY;
    private volatile CompiledRules compiled = CompiledRules.compile(RuleDocument.EMPTY);
    private volatile String mirroredCooldownGroup = "";
    private volatile TraversalProfile mirroredProfile = TraversalProfile.DEFAULT;

    RulesPortalExtension(LocalPortal portal) {
        this.portal = portal;
        charges.reshape(document.profile(), System.currentTimeMillis());
    }

    @Override
    public String key() {
        return KEY;
    }

    public LocalPortal portal() {
        return portal;
    }

    public RuleDocument document() {
        return document;
    }

    public ChargePool charges() {
        return charges;
    }

    /** The compiled chain for the current revision. */
    public CompiledRules compiled() {
        return compiled;
    }

    /** Replaces the document, bumps its revision, reshapes the charge pool and marks the portal dirty. */
    public void setDocument(RuleDocument replacement) {
        RuleDocument next = (replacement == null ? RuleDocument.EMPTY : replacement).withRevision(document.revision() + 1L);
        document = next;
        compiled = CompiledRules.compile(next);
        charges.reshape(next.profile(), System.currentTimeMillis());
        portal.save();
    }

    /** The cooldown group replicated from the linked portal, empty when there is none. */
    public String mirroredCooldownGroup() {
        return mirroredCooldownGroup;
    }

    /** The numeric profile replicated from the linked portal. */
    public TraversalProfile mirroredProfile() {
        return mirroredProfile;
    }

    @Override
    public void save(JSONObject portalJson) {
        if (!document.isInert()) {
            portalJson.put(DOCUMENT_KEY, RuleDocumentCodec.toJson(document));
        }
        if (!charges.unlimited()) {
            charges.save(portalJson, CHARGES_KEY);
        }
    }

    @Override
    public void load(JSONObject portalJson) {
        JSONObject stored = portalJson.optJSONObject(DOCUMENT_KEY);
        RuleDocument loaded = RuleDocument.EMPTY;
        if (stored != null) {
            List<String> problems = new ArrayList<>();
            RuleDocument decoded = RuleDocumentCodec.decode(stored, problems);
            if (problems.isEmpty()) {
                loaded = decoded;
            } else {
                Wormholes.w("rules document on portal " + portal.getId() + " rejected: " + String.join("; ", problems));
            }
        }
        document = loaded;
        compiled = CompiledRules.compile(loaded);
        charges.load(portalJson, CHARGES_KEY);
        charges.reshape(loaded.profile(), System.currentTimeMillis());
    }

    @Override
    public void collectSync(Map<String, String> settings) {
        TraversalProfile profile = document.profile();
        if (profile.isDefault()) {
            return;
        }
        settings.put(SYNC_COOLDOWN_GROUP, profile.cooldownGroup());
        settings.put(SYNC_PROFILE, profile.cooldownMillis() + "," + profile.warmupMillis() + ","
            + profile.pushbackScale() + "," + profile.soundVolume());
    }

    @Override
    public void applySync(Map<String, String> settings) {
        mirroredCooldownGroup = settings.getOrDefault(SYNC_COOLDOWN_GROUP, "");
        mirroredProfile = parseProfile(settings.get(SYNC_PROFILE), mirroredCooldownGroup);
    }

    private static TraversalProfile parseProfile(String encoded, String cooldownGroup) {
        if (encoded == null || encoded.isBlank()) {
            return TraversalProfile.DEFAULT;
        }
        String[] parts = encoded.split(",");
        if (parts.length != 4) {
            return TraversalProfile.DEFAULT;
        }
        try {
            return new TraversalProfile(Long.parseLong(parts[0].trim()), cooldownGroup, Long.parseLong(parts[1].trim()),
                Double.parseDouble(parts[2].trim()), Double.parseDouble(parts[3].trim()), 0, 0);
        } catch (NumberFormatException malformed) {
            return TraversalProfile.DEFAULT;
        }
    }
}
