package art.arcane.wormholes.modded;

import art.arcane.wormholes.access.PortalLookup;
import art.arcane.wormholes.nexus.DestinationEntry;
import art.arcane.wormholes.nexus.DestinationMode;
import art.arcane.wormholes.nexus.DestinationPolicy;
import art.arcane.wormholes.nexus.FrameIo;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.nexus.NetworkRole;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.nexus.SelectionRule;
import art.arcane.wormholes.nexus.Topology;
import art.arcane.wormholes.nexus.Visibility;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class MinecraftNexusCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final WormholesModRuntime runtime;

    public MinecraftNexusCommands(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> nexus = Commands.literal("nexus");
        for (String operation : List.of("list", "doctor")) {
            nexus.then(Commands.literal(operation).executes(context -> execute(context, operation, "", "", "")));
        }
        for (String operation : List.of("create", "delete", "info", "add", "remove", "address", "visibility", "topology", "hub", "role", "cooldown", "cost")) {
            nexus.then(Commands.literal(operation).then(Commands.argument("name", StringArgumentType.string())
                .executes(context -> execute(context, operation, value(context, "name"), "", ""))
                .then(Commands.argument("target", StringArgumentType.string())
                    .executes(context -> execute(context, operation, value(context, "name"), value(context, "target"), ""))
                    .then(Commands.argument("value", StringArgumentType.greedyString())
                        .executes(context -> execute(context, operation, value(context, "name"), value(context, "target"), value(context, "value")))))));
        }
        nexus.then(Commands.literal("portal").then(Commands.argument("portal", StringArgumentType.string())
            .executes(context -> portal(context, "menu", ""))
            .then(Commands.argument("action", StringArgumentType.word())
                .executes(context -> portal(context, value(context, "action"), ""))
                .then(Commands.argument("value", StringArgumentType.greedyString())
                    .executes(context -> portal(context, value(context, "action"), value(context, "value")))))));
        dispatcher.register(Commands.literal("wormholes").then(nexus));
    }

    private int execute(CommandContext<CommandSourceStack> context, String operation, String name, String target, String value) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer actor = source.getPlayerOrException();
            MinecraftNexus nexus = runtime.nexus();
            PortalNetwork network = nexus.networks().byName(name);
            if (operation.equals("list")) {
                for (PortalNetwork candidate : nexus.networks().all()) {
                    if (candidate.visibleTo(actor.getUUID(), nexus.administrator(actor))) {
                        reply(source, candidate.name() + " | " + candidate.members().size() + " | " + candidate.visibility());
                    }
                }
                return 1;
            }
            if (operation.equals("doctor")) {
                for (MinecraftPortal portal : runtime.portals().snapshot()) {
                    if (!runtime.portals().canManage(actor, portal) || portal.getDestinationId() == null) {
                        continue;
                    }
                    if (portal.getDestinationServer() != null) {
                        if (!runtime.network().manager().isPeerReady(portal.getDestinationServer())) {
                            reply(source, portal.getName() + " | peer unavailable: " + portal.getDestinationServer());
                        }
                    } else {
                        MinecraftPortal destination = runtime.portals().get(portal.getDestinationId());
                        if (destination == null) {
                            reply(source, portal.getName() + " | missing destination");
                        } else if (runtime.portals().resolveLevel(destination) == null) {
                            reply(source, portal.getName() + " | unloaded destination world");
                        } else if (!portal.getId().equals(destination.getDestinationId()) && nexus.networks().memberOf(portal.getId()) != null) {
                            reply(source, portal.getName() + " | no return link from " + destination.getName());
                        }
                    }
                }
                return 1;
            }
            if (operation.equals("create")) {
                network = nexus.create(actor, name);
                nexus.save(network.withVisibility(Visibility.parse(target, Visibility.MEMBERS)).withTopology(Topology.parse(value, Topology.MESH)));
                reply(source, network.name());
                return 1;
            }
            if (operation.equals("address")) {
                MinecraftPortal portal = portal(name);
                network = nexus.networks().memberOf(portal.getId());
                nexus.join(actor, network, portal, target.equalsIgnoreCase("auto") ? "" : target);
                reply(source, String.valueOf(portal.setting("nexus.address")));
                return 1;
            }
            if (network == null || !network.visibleTo(actor.getUUID(), nexus.administrator(actor))) {
                throw new IllegalArgumentException("Network is unavailable");
            }
            if (operation.equals("info")) {
                reply(source, network.name() + " | " + network.visibility() + " | " + network.topology());
                for (NetworkMember member : network.membersByAddress()) {
                    reply(source, member.address() + " | " + member.label() + " | " + member.portalId());
                }
                return 1;
            }
            nexus.requireManage(actor, network);
            switch (operation) {
                case "delete" -> nexus.delete(actor, network);
                case "add" -> nexus.join(actor, network, portal(target), value.equalsIgnoreCase("auto") ? "" : value);
                case "remove" -> {
                    MinecraftPortal portal = portal(target);
                    if (network.member(portal.getId()) == null) {
                        throw new IllegalArgumentException("Portal is not a member of this network");
                    }
                    nexus.leave(actor, portal);
                }
                case "visibility" -> nexus.save(network.withVisibility(Visibility.valueOf(target.toUpperCase(Locale.ROOT))));
                case "topology" -> nexus.save(network.withTopology(Topology.valueOf(target.toUpperCase(Locale.ROOT))));
                case "hub" -> {
                    MinecraftPortal portal = portal(target);
                    if (network.member(portal.getId()) == null) {
                        throw new IllegalArgumentException("Hub must belong to the network");
                    }
                    nexus.save(network.withHubPortalId(portal.getId()));
                }
                case "role" -> nexus.save(network.withRole(UUID.fromString(target), NetworkRole.valueOf(value.toUpperCase(Locale.ROOT))));
                case "cooldown" -> nexus.save(network.withCooldownGroup(target));
                case "cost" -> nexus.save(network.withDefaultCostTemplate(target));
                default -> throw new IllegalArgumentException("Unknown network operation");
            }
            reply(source, name);
            return 1;
        } catch (UncheckedIOException failure) {
            LOGGER.error("Could not update Wormholes network", failure);
            source.sendFailure(Component.literal(failure.getMessage()));
        } catch (CommandSyntaxException | IllegalArgumentException failure) {
            source.sendFailure(Component.literal(failure.getMessage()));
        }
        return 0;
    }

    private int portal(CommandContext<CommandSourceStack> context, String action, String value) {
        CommandSourceStack source = context.getSource();
        try {
            ServerPlayer actor = source.getPlayerOrException();
            MinecraftPortal portal = portal(value(context, "portal"));
            MinecraftNexus nexus = runtime.nexus();
            switch (action) {
                case "menu" -> {
                    if (runtime.portals().canManage(actor, portal)) {
                        nexus.menus().openManagement(actor, portal);
                    } else {
                        nexus.menus().open(actor, portal);
                    }
                }
                case "dial" -> {
                    if (value.isBlank()) {
                        nexus.menus().open(actor, portal);
                    } else if (!nexus.dial(actor, portal, value)) {
                        throw new IllegalArgumentException("Address is unavailable or dialing is still cooling down");
                    }
                }
                case "next", "previous" -> {
                    if (!nexus.next(actor, portal, action.equals("next") ? 1 : -1)) {
                        throw new IllegalArgumentException("No dial destination is available");
                    }
                }
                case "sticky" -> nexus.sticky(actor, portal, Boolean.parseBoolean(value));
                case "mode" -> nexus.policy(actor, portal, nexus.policy(portal).withMode(DestinationMode.valueOf(value.toUpperCase(Locale.ROOT))));
                case "selection" -> nexus.policy(actor, portal, nexus.policy(portal).withSelection(SelectionRule.valueOf(value.toUpperCase(Locale.ROOT))));
                case "clear" -> nexus.policy(actor, portal, DestinationPolicy.empty());
                case "entry" -> {
                    String[] fields = value.split("\\s+", 6);
                    if (fields.length < 2) {
                        throw new IllegalArgumentException("Expected kind target [weight from to label]");
                    }
                    DestinationEntry entry = new DestinationEntry(DestinationEntry.TargetKind.valueOf(fields[0].toUpperCase(Locale.ROOT)),
                        fields[1], fields.length > 2 ? Integer.parseInt(fields[2]) : 1, fields.length > 3 ? Integer.parseInt(fields[3]) : 0,
                        fields.length > 4 ? Integer.parseInt(fields[4]) : 0, fields.length > 5 ? fields[5] : "");
                    nexus.policy(actor, portal, nexus.policy(portal).withEntry(entry));
                }
                case "remove-entry" -> nexus.policy(actor, portal, nexus.policy(portal).withoutEntry(Integer.parseInt(value)));
                case "wire" -> {
                    String[] fields = value.split("\\s+");
                    if (fields.length != 5) {
                        throw new IllegalArgumentException("Expected x y z action comparator");
                    }
                    nexus.wire(actor, portal, new FrameIo(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                        FrameIo.RedstoneAction.valueOf(fields[3].toUpperCase(Locale.ROOT)), FrameIo.ComparatorOutput.valueOf(fields[4].toUpperCase(Locale.ROOT))));
                }
                case "pair", "unpair" -> nexus.pair(actor, portal, portal(value), action.equals("pair"));
                default -> throw new IllegalArgumentException("Unknown portal network action");
            }
            return 1;
        } catch (UncheckedIOException failure) {
            LOGGER.error("Could not update Wormholes portal network", failure);
            source.sendFailure(Component.literal(failure.getMessage()));
        } catch (CommandSyntaxException | IllegalArgumentException failure) {
            source.sendFailure(Component.literal(failure.getMessage()));
        }
        return 0;
    }

    private MinecraftPortal portal(String token) {
        PortalLookup.Match<MinecraftPortal> match = PortalLookup.find(token, runtime.portals().snapshot(), MinecraftPortal::getId, MinecraftPortal::getName);
        if (match.portal() == null) {
            throw new IllegalArgumentException("Portal is missing or ambiguous: " + token);
        }
        return match.portal();
    }

    private static String value(CommandContext<CommandSourceStack> context, String name) {
        return StringArgumentType.getString(context, name);
    }

    private static void reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }
}
