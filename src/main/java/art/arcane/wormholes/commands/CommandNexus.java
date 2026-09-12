package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.NexusMessages;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.nexus.AddressAllocator;
import art.arcane.wormholes.nexus.LinkDoctor;
import art.arcane.wormholes.nexus.LinkDoctorReport;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.nexus.NexusPortalExtension;
import art.arcane.wormholes.nexus.NexusSubsystem;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.nexus.Topology;
import art.arcane.wormholes.nexus.Visibility;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.service.WormholesAudience;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

/** Operator commands for portal networks: {@code /wh nexus ...}. */
@Director(name = "nexus", description = "Portal networks, addresses, and link health")
public class CommandNexus {
    /** Address value that asks the allocator for a fresh code instead of naming one. */
    static final String AUTO_ADDRESS = "auto";

    private final Random random = new Random();

    @Director(name = "create", sync = true, description = "Create a portal network")
    public void create(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "name", description = "Network name") String name,
                       @Param(name = "visibility", description = "public | members | hidden", defaultValue = "members") String visibility,
                       @Param(name = "topology", description = "mesh | hub | chain | ring", defaultValue = "mesh") String topology) {
        if (!allowed(sender)) {
            return;
        }
        NetworkRegistry registry = registry(sender);
        if (registry == null) {
            return;
        }
        if (registry.byName(name) != null) {
            send(sender, NexusMessages.NETWORK_EXISTS, args("name", name));
            return;
        }
        PortalNetwork network = PortalNetwork.create(UUID.randomUUID(), name, senderId(sender))
                .withVisibility(Visibility.parse(visibility, Visibility.MEMBERS))
                .withTopology(Topology.parse(topology, Topology.MESH));
        if (save(sender, registry, network)) {
            send(sender, NexusMessages.CREATED, args("name", network.name()));
        }
    }

    @Director(name = "delete", sync = true, description = "Delete a portal network and clear its addresses")
    public void delete(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "name", description = "Network name") String name) {
        if (!allowed(sender)) {
            return;
        }
        NetworkRegistry registry = registry(sender);
        PortalNetwork network = registry == null ? null : registry.byName(name);
        if (network == null) {
            send(sender, NexusMessages.NETWORK_UNKNOWN, args("name", name));
            return;
        }
        for (UUID portalId : network.members().keySet()) {
            NexusPortalExtension state = state(portalId);
            if (state != null) {
                state.setNetworkId(null);
                state.setAddress("");
            }
        }
        try {
            registry.delete(network.id());
            send(sender, NexusMessages.DELETED, args("name", network.name()));
        } catch (IOException failure) {
            log("delete network " + network.name(), failure);
        }
    }

    @Director(name = "add", sync = true, description = "Add a portal to a network")
    public void add(@Param(name = "sender", contextual = true) CommandSender sender,
                    @Param(name = "network", description = "Network name") String networkName,
                    @Param(name = "portal", description = "Portal name") String portalName,
                    @Param(name = "address", description = "Address to use, or auto for a fresh one", defaultValue = AUTO_ADDRESS) String address) {
        if (!allowed(sender)) {
            return;
        }
        NetworkRegistry registry = registry(sender);
        PortalNetwork network = registry == null ? null : registry.byName(networkName);
        if (network == null) {
            send(sender, NexusMessages.NETWORK_UNKNOWN, args("name", networkName));
            return;
        }
        LocalPortal portal = portal(portalName);
        if (portal == null) {
            send(sender, NexusMessages.PORTAL_UNKNOWN, args("portal", portalName));
            return;
        }
        int limit = NexusSubsystem.config().maxMembersPerNetwork;
        if (network.members().size() >= limit && !network.members().containsKey(portal.getId())) {
            send(sender, NexusMessages.NETWORK_FULL, args("name", network.name(), "count", limit));
            return;
        }
        String alphabet = NexusSubsystem.config().addressAlphabet;
        String resolved;
        if (address.isBlank() || AUTO_ADDRESS.equalsIgnoreCase(address.trim())) {
            resolved = AddressAllocator.next(network, alphabet, NexusSubsystem.config().addressLength, random);
        } else {
            if (!AddressAllocator.isValid(address, alphabet)) {
                send(sender, NexusMessages.ADDRESS_INVALID, args("value", alphabet));
                return;
            }
            resolved = NetworkMember.normalizeAddress(address);
            UUID holder = network.portalIdAt(resolved);
            if (holder != null && !holder.equals(portal.getId())) {
                send(sender, NexusMessages.ADDRESS_TAKEN, args("address", resolved, "name", network.name()));
                return;
            }
        }
        PortalNetwork joined = network.withMember(portal.getId(),
                new NetworkMember(portal.getId(), resolved, portal.getName(), System.currentTimeMillis(), null));
        if (!save(sender, registry, joined)) {
            return;
        }
        NexusPortalExtension state = portal.extension(NexusPortalExtension.class);
        state.setNetworkId(joined.id());
        state.setAddress(resolved);
        state.setLabel(portal.getName());
        portal.save();
        send(sender, NexusMessages.JOINED,
                args("portal", portal.getName(), "name", joined.name(), "address", resolved));
    }

    @Director(name = "remove", sync = true, description = "Remove a portal from a network")
    public void remove(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "network", description = "Network name") String networkName,
                       @Param(name = "portal", description = "Portal name") String portalName) {
        if (!allowed(sender)) {
            return;
        }
        NetworkRegistry registry = registry(sender);
        PortalNetwork network = registry == null ? null : registry.byName(networkName);
        if (network == null) {
            send(sender, NexusMessages.NETWORK_UNKNOWN, args("name", networkName));
            return;
        }
        LocalPortal portal = portal(portalName);
        if (portal == null) {
            send(sender, NexusMessages.PORTAL_UNKNOWN, args("portal", portalName));
            return;
        }
        if (!network.members().containsKey(portal.getId())) {
            send(sender, NexusMessages.PORTAL_NOT_MEMBER, args("portal", portal.getName(), "name", network.name()));
            return;
        }
        if (!save(sender, registry, network.withoutMember(portal.getId()))) {
            return;
        }
        NexusPortalExtension state = portal.extension(NexusPortalExtension.class);
        state.setNetworkId(null);
        state.setAddress("");
        portal.save();
        send(sender, NexusMessages.LEFT, args("portal", portal.getName(), "name", network.name()));
    }

    @Director(name = "list", sync = true, description = "List every portal network")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!allowed(sender)) {
            return;
        }
        NetworkRegistry registry = registry(sender);
        if (registry == null) {
            return;
        }
        if (registry.all().isEmpty()) {
            send(sender, NexusMessages.LIST_EMPTY);
            return;
        }
        for (PortalNetwork network : registry.all()) {
            send(sender, NexusMessages.LIST_ENTRY, args(
                    "name", network.name(),
                    "count", network.members().size(),
                    "value", network.visibility().name(),
                    "owner", ownerName(network)));
        }
    }

    @Director(name = "info", sync = true, description = "Show a network's members and addresses")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "network", description = "Network name") String networkName) {
        if (!allowed(sender)) {
            return;
        }
        NetworkRegistry registry = registry(sender);
        PortalNetwork network = registry == null ? null : registry.byName(networkName);
        if (network == null) {
            send(sender, NexusMessages.NETWORK_UNKNOWN, args("name", networkName));
            return;
        }
        send(sender, NexusMessages.INFO_HEADER, args(
                "name", network.name(),
                "count", network.members().size(),
                "value", network.visibility().name(),
                "mode", network.topology().name()));
        for (NetworkMember member : network.membersByAddress()) {
            ILocalPortal portal = portalById(member.portalId());
            send(sender, NexusMessages.INFO_MEMBER, args(
                    "address", member.address(),
                    "portal", portal == null ? member.label() : portal.getName(),
                    "world", member.isLocal() ? worldName(portal) : member.serverName(),
                    "state", portal == null && member.isLocal() ? "missing" : "ok"));
        }
    }

    @Director(name = "address", sync = true, description = "Set a portal's address on its network")
    public void address(@Param(name = "sender", contextual = true) CommandSender sender,
                        @Param(name = "portal", description = "Portal name") String portalName,
                        @Param(name = "address", description = "New address") String address) {
        if (!allowed(sender)) {
            return;
        }
        NetworkRegistry registry = registry(sender);
        LocalPortal portal = portal(portalName);
        if (portal == null) {
            send(sender, NexusMessages.PORTAL_UNKNOWN, args("portal", portalName));
            return;
        }
        NexusPortalExtension state = portal.extension(NexusPortalExtension.class);
        PortalNetwork network = registry == null || state == null ? null : registry.byId(state.networkId());
        if (network == null) {
            send(sender, NexusMessages.DIAL_NONE, args("portal", portal.getName()));
            return;
        }
        String alphabet = NexusSubsystem.config().addressAlphabet;
        if (!AddressAllocator.isValid(address, alphabet)) {
            send(sender, NexusMessages.ADDRESS_INVALID, args("value", alphabet));
            return;
        }
        String resolved = NetworkMember.normalizeAddress(address);
        UUID holder = network.portalIdAt(resolved);
        if (holder != null && !holder.equals(portal.getId())) {
            send(sender, NexusMessages.ADDRESS_TAKEN, args("address", resolved, "name", network.name()));
            return;
        }
        NetworkMember member = network.member(portal.getId());
        if (member == null) {
            send(sender, NexusMessages.PORTAL_NOT_MEMBER, args("portal", portal.getName(), "name", network.name()));
            return;
        }
        if (!save(sender, registry, network.withMember(portal.getId(), member.withAddress(resolved)))) {
            return;
        }
        state.setAddress(resolved);
        portal.save();
        send(sender, NexusMessages.ADDRESS_SET, args("portal", portal.getName(), "address", resolved));
    }

    @Director(name = "visibility", sync = true, description = "Set who may see a network listed")
    public void visibility(@Param(name = "sender", contextual = true) CommandSender sender,
                           @Param(name = "network", description = "Network name") String networkName,
                           @Param(name = "value", description = "public | members | hidden") String value) {
        if (!allowed(sender)) {
            return;
        }
        NetworkRegistry registry = registry(sender);
        PortalNetwork network = registry == null ? null : registry.byName(networkName);
        if (network == null) {
            send(sender, NexusMessages.NETWORK_UNKNOWN, args("name", networkName));
            return;
        }
        PortalNetwork updated = network.withVisibility(Visibility.parse(value, network.visibility()));
        if (save(sender, registry, updated)) {
            send(sender, NexusMessages.VISIBILITY_SET,
                    args("name", updated.name(), "value", updated.visibility().name()));
        }
    }

    @Director(name = "hub", sync = true, description = "Point a hub or ring network at its central portal")
    public void hub(@Param(name = "sender", contextual = true) CommandSender sender,
                    @Param(name = "network", description = "Network name") String networkName,
                    @Param(name = "portal", description = "Portal name") String portalName) {
        if (!allowed(sender)) {
            return;
        }
        NetworkRegistry registry = registry(sender);
        PortalNetwork network = registry == null ? null : registry.byName(networkName);
        if (network == null) {
            send(sender, NexusMessages.NETWORK_UNKNOWN, args("name", networkName));
            return;
        }
        LocalPortal portal = portal(portalName);
        if (portal == null) {
            send(sender, NexusMessages.PORTAL_UNKNOWN, args("portal", portalName));
            return;
        }
        if (!network.members().containsKey(portal.getId())) {
            send(sender, NexusMessages.PORTAL_NOT_MEMBER, args("portal", portal.getName(), "name", network.name()));
            return;
        }
        if (save(sender, registry, network.withHubPortalId(portal.getId()))) {
            send(sender, NexusMessages.HUB_SET, args("portal", portal.getName(), "name", network.name()));
        }
    }

    @Director(name = "doctor", sync = true, description = "Report one-way, dangling, unloaded and offline links")
    public void doctor(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!allowed(sender)) {
            return;
        }
        NetworkRegistry registry = registry(sender);
        if (registry == null || Wormholes.portalManager == null) {
            return;
        }
        LinkDoctorReport report = LinkDoctor.inspect(Wormholes.portalManager.getLocalPortals(), registry,
                LinkDoctor.bukkitEnvironment());
        if (report.isClean()) {
            send(sender, NexusMessages.DOCTOR_CLEAN);
            return;
        }
        send(sender, NexusMessages.DOCTOR_HEADER, args("count", report.findings().size()));
        for (LinkDoctorReport.Finding finding : report.findings()) {
            switch (finding.kind()) {
                case ONE_WAY -> send(sender, NexusMessages.DOCTOR_ONEWAY,
                        args("portal", finding.portal(), "destination", finding.destination()));
                case DANGLING -> send(sender, NexusMessages.DOCTOR_DANGLING, args("portal", finding.portal()));
                case UNLOADED_WORLD -> send(sender, NexusMessages.DOCTOR_UNLOADED,
                        args("portal", finding.portal(), "world", finding.world()));
                case PEER_OFFLINE -> send(sender, NexusMessages.DOCTOR_OFFLINE, args(
                        "portal", finding.portal(), "server", finding.server(), "count", finding.offlineDays()));
            }
        }
    }

    private static boolean allowed(CommandSender sender) {
        if (sender.hasPermission("wormholes.admin.nexus")) {
            return true;
        }
        send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
        return false;
    }

    private static NetworkRegistry registry(CommandSender sender) {
        NexusSubsystem nexus = NexusSubsystem.active();
        NetworkRegistry registry = nexus == null ? null : nexus.registry();
        if (registry == null) {
            send(sender, NexusMessages.LIST_EMPTY);
        }
        return registry;
    }

    private static boolean save(CommandSender sender, NetworkRegistry registry, PortalNetwork network) {
        try {
            registry.save(network);
            return true;
        } catch (IOException failure) {
            log("save network " + network.name(), failure);
            send(sender, NexusMessages.NETWORK_UNKNOWN, args("name", network.name()));
            return false;
        }
    }

    private static LocalPortal portal(String name) {
        if (Wormholes.portalManager == null || name == null || name.isBlank()) {
            return null;
        }
        List<ILocalPortal> portals = Wormholes.portalManager.getLocalPortals();
        for (ILocalPortal candidate : portals) {
            if (candidate instanceof LocalPortal local && local.getName().equalsIgnoreCase(name.trim())) {
                return local;
            }
        }
        return null;
    }

    private static ILocalPortal portalById(UUID portalId) {
        return Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(portalId);
    }

    private static NexusPortalExtension state(UUID portalId) {
        ILocalPortal portal = portalById(portalId);
        return portal instanceof LocalPortal local ? local.extension(NexusPortalExtension.class) : null;
    }

    private static String worldName(ILocalPortal portal) {
        if (portal == null || portal.getStructure() == null || portal.getStructure().getWorld() == null) {
            return "";
        }
        return portal.getStructure().getWorld().getName();
    }

    private static String ownerName(PortalNetwork network) {
        if (network.ownerId() == null) {
            return "";
        }
        return Wormholes.instance == null
                ? network.ownerId().toString()
                : String.valueOf(Wormholes.instance.getServer().getOfflinePlayer(network.ownerId()).getName());
    }

    private static UUID senderId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    private static void log(String what, Throwable failure) {
        if (Wormholes.instance != null) {
            Wormholes.instance.getLogger().log(Level.WARNING, "nexus could not " + what, failure);
        }
    }

    private static void send(CommandSender sender, TextKey key) {
        send(sender, key, MessageArgs.empty());
    }

    private static void send(CommandSender sender, TextKey key, MessageArgs arguments) {
        WormholesAudience.sendMessage(sender, Wormholes.text().component(sender, key, arguments));
    }

    private static MessageArgs args(Object... nameValuePairs) {
        MessageArgument[] arguments = new MessageArgument[nameValuePairs.length / 2];
        for (int index = 0; index < nameValuePairs.length; index += 2) {
            arguments[index / 2] = MessageArgument.untrusted((String) nameValuePairs[index], nameValuePairs[index + 1]);
        }
        return WormholesLocalization.args(arguments);
    }
}
