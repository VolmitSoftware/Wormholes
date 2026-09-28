package art.arcane.wormholes.nexus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import java.util.Objects;
import java.util.UUID;

/** Reads and writes one {@code atlas/networks/&lt;uuid&gt;.json} document. Unknown keys are dropped on rewrite. */
public final class NetworkRegistryCodec {
    private NetworkRegistryCodec() {
    }

    public static Map<String, Object> encode(PortalNetwork network) {
        Objects.requireNonNull(network, "network");
        Map<String, Object> json = new LinkedHashMap<>();
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

        List<Object> members = new ArrayList<>();
        for (Map.Entry<UUID, NetworkMember> entry : network.members().entrySet()) {
            NetworkMember member = entry.getValue();
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("portalId", entry.getKey().toString());
            encoded.put("address", member.address());
            encoded.put("label", member.label());
            encoded.put("joinedAt", member.joinedAtMillis());
            if (member.serverName() != null) {
                encoded.put("server", member.serverName());
            }
            members.add(encoded);
        }
        json.put("members", members);

        List<Object> roster = new ArrayList<>();
        for (Map.Entry<UUID, NetworkRole> entry : network.roster().entrySet()) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("player", entry.getKey().toString());
            encoded.put("role", entry.getValue().name());
            roster.add(encoded);
        }
        json.put("roster", roster);
        return json;
    }

    public static PortalNetwork decode(Map<String, Object> json) {
        Objects.requireNonNull(json, "json");
        UUID id = UUID.fromString(NexusValues.string(json, "id", ""));
        String name = NexusValues.string(json, "name", id.toString());
        UUID ownerId = optionalUuid(NexusValues.string(json, "ownerId", ""));
        Visibility visibility = Visibility.parse(NexusValues.string(json, "visibility", ""), Visibility.MEMBERS);
        Topology topology = Topology.parse(NexusValues.string(json, "topology", ""), Topology.MESH);
        UUID hubPortalId = optionalUuid(NexusValues.string(json, "hubPortalId", ""));

        Map<UUID, NetworkMember> members = new LinkedHashMap<>();
        List<?> encodedMembers = NexusValues.list(json.get("members"));
        if (encodedMembers != null) {
            for (int index = 0; index < encodedMembers.size(); index++) {
                Map<String, Object> encoded = NexusValues.object(encodedMembers.get(index));
                if (encoded == null) {
                    continue;
                }
                UUID portalId = optionalUuid(NexusValues.string(encoded, "portalId", ""));
                String address = NexusValues.string(encoded, "address", "");
                if (portalId == null || address.isBlank()) {
                    continue;
                }
                members.put(portalId, new NetworkMember(portalId, address, NexusValues.string(encoded, "label", ""),
                        NexusValues.number(encoded, "joinedAt", 0L), NexusValues.string(encoded, "server", "")));
            }
        }

        Map<UUID, NetworkRole> roster = new LinkedHashMap<>();
        List<?> encodedRoster = NexusValues.list(json.get("roster"));
        if (encodedRoster != null) {
            for (int index = 0; index < encodedRoster.size(); index++) {
                Map<String, Object> encoded = NexusValues.object(encodedRoster.get(index));
                if (encoded == null) {
                    continue;
                }
                UUID playerId = optionalUuid(NexusValues.string(encoded, "player", ""));
                if (playerId == null) {
                    continue;
                }
                roster.put(playerId, NetworkRole.parse(NexusValues.string(encoded, "role", ""), NetworkRole.MEMBER));
            }
        }
        if (ownerId != null) {
            roster.putIfAbsent(ownerId, NetworkRole.OWNER);
        }

        return new PortalNetwork(id, name, ownerId, visibility, topology, hubPortalId, members, roster,
                NexusValues.string(json, "cooldownGroup", ""), NexusValues.string(json, "defaultCostTemplate", ""),
                NexusValues.string(json, "serverName", ""));
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
