package art.arcane.wormholes.nexus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A named group of portals with short dial addresses. Immutable: every {@code with*} returns a copy,
 * and {@link NetworkRegistry#save(PortalNetwork)} persists the copy.
 */
public record PortalNetwork(UUID id, String name, UUID ownerId, Visibility visibility, Topology topology,
                            UUID hubPortalId, Map<UUID, NetworkMember> members, Map<UUID, NetworkRole> roster,
                            String cooldownGroup, String defaultCostTemplate, String serverName) {
    public PortalNetwork {
        Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name").trim();
        visibility = visibility == null ? Visibility.MEMBERS : visibility;
        topology = topology == null ? Topology.MESH : topology;
        members = members == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(members));
        roster = roster == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(roster));
        cooldownGroup = cooldownGroup == null ? "" : cooldownGroup;
        defaultCostTemplate = defaultCostTemplate == null ? "" : defaultCostTemplate;
        serverName = serverName == null || serverName.isBlank() ? null : serverName.trim();
    }

    public static PortalNetwork create(UUID id, String name, UUID ownerId) {
        Map<UUID, NetworkRole> roster = new LinkedHashMap<>();
        if (ownerId != null) {
            roster.put(ownerId, NetworkRole.OWNER);
        }
        return new PortalNetwork(id, name, ownerId, Visibility.MEMBERS, Topology.MESH, null, Map.of(), roster, "", "", null);
    }

    public NetworkMember member(UUID portalId) {
        return portalId == null ? null : members.get(portalId);
    }

    public NetworkRole role(UUID playerId) {
        return playerId == null ? null : roster.get(playerId);
    }

    /** Who may see this network and its addresses: PUBLIC is open, anything else needs the roster. */
    public boolean visibleTo(UUID playerId, boolean administrator) {
        if (visibility == Visibility.PUBLIC || administrator) {
            return true;
        }
        return playerId != null && (playerId.equals(ownerId) || roster.containsKey(playerId));
    }

    public boolean usesAddress(String address) {
        return portalIdAt(address) != null;
    }

    public UUID portalIdAt(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String normalized = NetworkMember.normalizeAddress(address);
        for (Map.Entry<UUID, NetworkMember> entry : members.entrySet()) {
            if (entry.getValue().address().equals(normalized)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public NetworkMember memberAt(String address) {
        UUID portalId = portalIdAt(address);
        return portalId == null ? null : members.get(portalId);
    }

    /** Members in address order; the dial gesture and the dial menu both walk this list. */
    public List<NetworkMember> membersByAddress() {
        List<NetworkMember> ordered = new ArrayList<>(members.values());
        ordered.sort(Comparator.comparing(NetworkMember::address));
        return ordered;
    }

    public boolean matchesName(String candidate) {
        return candidate != null && name.toLowerCase(Locale.ROOT).equals(candidate.trim().toLowerCase(Locale.ROOT));
    }

    public PortalNetwork withName(String newName) {
        return new PortalNetwork(id, newName, ownerId, visibility, topology, hubPortalId, members, roster,
                cooldownGroup, defaultCostTemplate, serverName);
    }

    public PortalNetwork withVisibility(Visibility newVisibility) {
        return new PortalNetwork(id, name, ownerId, newVisibility, topology, hubPortalId, members, roster,
                cooldownGroup, defaultCostTemplate, serverName);
    }

    public PortalNetwork withTopology(Topology newTopology) {
        return new PortalNetwork(id, name, ownerId, visibility, newTopology, hubPortalId, members, roster,
                cooldownGroup, defaultCostTemplate, serverName);
    }

    public PortalNetwork withHubPortalId(UUID newHubPortalId) {
        return new PortalNetwork(id, name, ownerId, visibility, topology, newHubPortalId, members, roster,
                cooldownGroup, defaultCostTemplate, serverName);
    }

    public PortalNetwork withMembers(Map<UUID, NetworkMember> newMembers) {
        return new PortalNetwork(id, name, ownerId, visibility, topology, hubPortalId, newMembers, roster,
                cooldownGroup, defaultCostTemplate, serverName);
    }

    public PortalNetwork withMember(UUID portalId, NetworkMember member) {
        Map<UUID, NetworkMember> copy = new LinkedHashMap<>(members);
        copy.put(Objects.requireNonNull(portalId, "portalId"), Objects.requireNonNull(member, "member"));
        return withMembers(copy);
    }

    public PortalNetwork withoutMember(UUID portalId) {
        if (!members.containsKey(portalId)) {
            return this;
        }
        Map<UUID, NetworkMember> copy = new LinkedHashMap<>(members);
        copy.remove(portalId);
        UUID hub = portalId.equals(hubPortalId) ? null : hubPortalId;
        return new PortalNetwork(id, name, ownerId, visibility, topology, hub, copy, roster,
                cooldownGroup, defaultCostTemplate, serverName);
    }

    public PortalNetwork withRole(UUID playerId, NetworkRole role) {
        Map<UUID, NetworkRole> copy = new LinkedHashMap<>(roster);
        if (role == null) {
            copy.remove(playerId);
        } else {
            copy.put(Objects.requireNonNull(playerId, "playerId"), role);
        }
        return new PortalNetwork(id, name, ownerId, visibility, topology, hubPortalId, members, copy,
                cooldownGroup, defaultCostTemplate, serverName);
    }

    public PortalNetwork withCooldownGroup(String newCooldownGroup) {
        return new PortalNetwork(id, name, ownerId, visibility, topology, hubPortalId, members, roster,
                newCooldownGroup, defaultCostTemplate, serverName);
    }

    public PortalNetwork withDefaultCostTemplate(String newDefaultCostTemplate) {
        return new PortalNetwork(id, name, ownerId, visibility, topology, hubPortalId, members, roster,
                cooldownGroup, newDefaultCostTemplate, serverName);
    }

    public PortalNetwork withServerName(String newServerName) {
        return new PortalNetwork(id, name, ownerId, visibility, topology, hubPortalId, members, roster,
                cooldownGroup, defaultCostTemplate, newServerName);
    }
}
