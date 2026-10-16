package art.arcane.wormholes.menu.nexus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.config.toml.NexusConfig;
import art.arcane.wormholes.menu.FakeStrings;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.nexus.DialState;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.portal.PortalType;

final class FakeNexusHost implements NetworkMenuHost, DialMenuHost {
    static final UUID PORTAL = UUID.fromString("00000000-0000-0000-0000-00000000a1fa");
    static final UUID NETWORK = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";

    final Set<UUID> managers = new HashSet<>();
    final Set<UUID> dialers = new HashSet<>();
    final Map<UUID, PortalNetwork> networks = new LinkedHashMap<>();
    final Map<UUID, Integer> owned = new HashMap<>();
    final Map<UUID, String> worlds = new HashMap<>();
    final List<String> edits = new ArrayList<>();
    final List<PortalNetwork> saved = new ArrayList<>();
    final List<String> told = new ArrayList<>();
    final List<String> dials = new ArrayList<>();
    final NexusConfig config = new NexusConfig();
    NexusPortalView portal = view(null, "");
    boolean present = true;
    boolean saveFails;
    int portalLookups;
    DialOutcome outcome = DialOutcome.DIALED;

    static NexusPortalView view(UUID networkId, String address) {
        return new NexusPortalView(PORTAL, "Alpha", PortalType.PORTAL, false, "§0§lAlpha", networkId, address, false, null, null, null);
    }

    static PortalNetwork lobby(int otherMembers) {
        PortalNetwork network = PortalNetwork.create(NETWORK, "Lobby", UUID.fromString("00000000-0000-0000-0000-0000000000a0"));
        network = network.withMember(PORTAL, new NetworkMember(PORTAL, "AAAA", "Alpha", 1L, null));
        for (int index = 0; index < otherMembers; index++) {
            UUID memberId = new UUID(7L, index);
            String address = "B" + LETTERS.charAt(index / LETTERS.length()) + LETTERS.charAt(index % LETTERS.length());
            network = network.withMember(memberId, new NetworkMember(memberId, address, "Member " + index, 2L, index % 2 == 0 ? null : "east"));
        }
        return network;
    }

    void join(PortalNetwork network) {
        networks.put(network.id(), network);
        portal = view(network.id(), network.member(PORTAL).address());
    }

    @Override
    public Optional<NexusPortalView> portal(UUID portalId) {
        portalLookups++;
        return present && portalId.equals(PORTAL) ? Optional.of(portal) : Optional.empty();
    }

    @Override
    public boolean canManage(MenuViewer viewer, UUID portalId) {
        return viewer.administrator() || managers.contains(viewer.id());
    }

    @Override
    public boolean dialAdmission(MenuViewer viewer, UUID portalId) {
        return viewer.administrator() || dialers.contains(viewer.id());
    }

    @Override
    public Optional<PortalNetwork> network(UUID networkId) {
        return Optional.ofNullable(networkId == null ? null : networks.get(networkId));
    }

    @Override
    public Optional<PortalNetwork> networkByName(String name) {
        for (PortalNetwork network : networks.values()) {
            if (network.matchesName(name)) {
                return Optional.of(network);
            }
        }
        return Optional.empty();
    }

    @Override
    public int networksOwnedBy(UUID playerId) {
        return owned.getOrDefault(playerId, 0);
    }

    @Override
    public NexusConfig config() {
        return config;
    }

    @Override
    public boolean saveNetwork(PortalNetwork network) {
        if (saveFails) {
            return false;
        }
        networks.put(network.id(), network);
        saved.add(network);
        return true;
    }

    @Override
    public void edit(MenuViewer viewer, UUID portalId, NetworkEdit edit) {
        edits.add(edit.toString());
        NexusPortalView current = portal;
        portal = switch (edit) {
            case NetworkEdit.Join join -> new NexusPortalView(current.id(), current.name(), current.type(), current.mirror(), current.routerTitle(),
                join.networkId(), join.address(), current.reciprocal(), current.policy(), current.frameIo(), current.dial());
            case NetworkEdit.Leave _ -> new NexusPortalView(current.id(), current.name(), current.type(), current.mirror(), current.routerTitle(),
                null, "", current.reciprocal(), current.policy(), current.frameIo(), DialState.idle());
            case NetworkEdit.Address address -> new NexusPortalView(current.id(), current.name(), current.type(), current.mirror(),
                current.routerTitle(), current.networkId(), address.address(), current.reciprocal(), current.policy(), current.frameIo(), current.dial());
            case NetworkEdit.Reciprocal reciprocal -> new NexusPortalView(current.id(), current.name(), current.type(), current.mirror(),
                current.routerTitle(), current.networkId(), current.address(), reciprocal.enabled(), current.policy(), current.frameIo(), current.dial());
            case NetworkEdit.Policy policy -> new NexusPortalView(current.id(), current.name(), current.type(), current.mirror(),
                current.routerTitle(), current.networkId(), current.address(), current.reciprocal(), policy.policy(), current.frameIo(), current.dial());
            case NetworkEdit.Redstone redstone -> new NexusPortalView(current.id(), current.name(), current.type(), current.mirror(),
                current.routerTitle(), current.networkId(), current.address(), current.reciprocal(), current.policy(), redstone.frameIo(), current.dial());
        };
    }

    @Override
    public void tell(MenuViewer viewer, TextKey key, MessageArgs arguments) {
        told.add(FakeStrings.render(key.english(), arguments));
    }

    @Override
    public String worldName(UUID portalId) {
        return worlds.getOrDefault(portalId, "world");
    }

    @Override
    public DialOutcome dial(MenuViewer viewer, UUID portalId, String address) {
        dials.add(address);
        return outcome;
    }
}
