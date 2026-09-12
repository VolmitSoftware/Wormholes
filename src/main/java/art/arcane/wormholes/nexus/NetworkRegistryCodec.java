package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Reads and writes one {@code atlas/networks/&lt;uuid&gt;.json} document. Unknown keys are dropped on rewrite. */
public final class NetworkRegistryCodec {
    private NetworkRegistryCodec() {
    }

    public static JSONObject encode(PortalNetwork network) {
        Objects.requireNonNull(network, "network");
        JSONObject json = new JSONObject();
        json.put("id", network.id().toString());
        json.put("name", network.name());
        if (network.ownerId() != null) {
            json.put("ownerId", network.ownerId().toString());
        }
        json.put("visibility", network.visibility().name());
        json.put("topology", network.topology().name());
        if (network.hubPortalId() != null) {
            json.put("hubPortalId", network.hubPortalId().toString());
        }
        if (!network.cooldownGroup().isEmpty()) {
            json.put("cooldownGroup", network.cooldownGroup());
        }
        if (!network.defaultCostTemplate().isEmpty()) {
            json.put("defaultCostTemplate", network.defaultCostTemplate());
        }
        if (network.serverName() != null) {
            json.put("serverName", network.serverName());
        }

        JSONArray members = new JSONArray();
        for (Map.Entry<UUID, NetworkMember> entry : network.members().entrySet()) {
            NetworkMember member = entry.getValue();
            JSONObject encoded = new JSONObject();
            encoded.put("portalId", entry.getKey().toString());
            encoded.put("address", member.address());
            encoded.put("label", member.label());
            encoded.put("joinedAt", member.joinedAtMillis());
            if (member.serverName() != null) {
                encoded.put("server", member.serverName());
            }
            members.put(encoded);
        }
        json.put("members", members);

        JSONArray roster = new JSONArray();
        for (Map.Entry<UUID, NetworkRole> entry : network.roster().entrySet()) {
            JSONObject encoded = new JSONObject();
            encoded.put("player", entry.getKey().toString());
            encoded.put("role", entry.getValue().name());
            roster.put(encoded);
        }
        json.put("roster", roster);
        return json;
    }

    public static PortalNetwork decode(JSONObject json) {
        Objects.requireNonNull(json, "json");
        UUID id = UUID.fromString(json.getString("id"));
        String name = json.optString("name", id.toString());
        UUID ownerId = optionalUuid(json.optString("ownerId", ""));
        Visibility visibility = Visibility.parse(json.optString("visibility", ""), Visibility.MEMBERS);
        Topology topology = Topology.parse(json.optString("topology", ""), Topology.MESH);
        UUID hubPortalId = optionalUuid(json.optString("hubPortalId", ""));

        Map<UUID, NetworkMember> members = new LinkedHashMap<>();
        JSONArray encodedMembers = json.optJSONArray("members");
        if (encodedMembers != null) {
            for (int index = 0; index < encodedMembers.length(); index++) {
                JSONObject encoded = encodedMembers.optJSONObject(index);
                if (encoded == null) {
                    continue;
                }
                UUID portalId = optionalUuid(encoded.optString("portalId", ""));
                String address = encoded.optString("address", "");
                if (portalId == null || address.isBlank()) {
                    continue;
                }
                members.put(portalId, new NetworkMember(portalId, address, encoded.optString("label", ""),
                        encoded.optLong("joinedAt", 0L), encoded.optString("server", "")));
            }
        }

        Map<UUID, NetworkRole> roster = new LinkedHashMap<>();
        JSONArray encodedRoster = json.optJSONArray("roster");
        if (encodedRoster != null) {
            for (int index = 0; index < encodedRoster.length(); index++) {
                JSONObject encoded = encodedRoster.optJSONObject(index);
                if (encoded == null) {
                    continue;
                }
                UUID playerId = optionalUuid(encoded.optString("player", ""));
                if (playerId == null) {
                    continue;
                }
                roster.put(playerId, NetworkRole.parse(encoded.optString("role", ""), NetworkRole.MEMBER));
            }
        }
        if (ownerId != null) {
            roster.putIfAbsent(ownerId, NetworkRole.OWNER);
        }

        return new PortalNetwork(id, name, ownerId, visibility, topology, hubPortalId, members, roster,
                json.optString("cooldownGroup", ""), json.optString("defaultCostTemplate", ""),
                json.optString("serverName", ""));
    }

    private static UUID optionalUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
