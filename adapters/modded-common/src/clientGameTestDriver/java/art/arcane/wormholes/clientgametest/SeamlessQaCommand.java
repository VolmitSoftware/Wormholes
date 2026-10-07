package art.arcane.wormholes.clientgametest;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class SeamlessQaCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String ARGUMENT = "arguments";

    private SeamlessQaCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wormholesqa")
            .then(Commands.literal("route").then(Commands.argument(ARGUMENT, StringArgumentType.greedyString()).executes(SeamlessQaCommand::route)))
            .then(Commands.literal("approach").then(Commands.argument(ARGUMENT, StringArgumentType.greedyString()).executes(SeamlessQaCommand::approach)))
            .then(Commands.literal("remove").then(Commands.argument(ARGUMENT, StringArgumentType.greedyString()).executes(SeamlessQaCommand::remove)))
            .then(Commands.literal("light").then(Commands.argument(ARGUMENT, StringArgumentType.greedyString()).executes(SeamlessQaCommand::light)))
            .then(Commands.literal("centers").then(Commands.argument(ARGUMENT, StringArgumentType.greedyString()).executes(SeamlessQaCommand::centers)))
            .then(Commands.literal("lit").then(Commands.argument(ARGUMENT, StringArgumentType.greedyString()).executes(SeamlessQaCommand::lit)))
            .then(Commands.literal("approachfrom").then(Commands.argument(ARGUMENT, StringArgumentType.greedyString())
                .executes(SeamlessQaCommand::approachFrom))));
    }

    private static int route(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MinecraftServer server = context.getSource().getServer();
        try {
            SeamlessScenario.Route route = SeamlessScenario.build(player, server, RouteCodec.spec(StringArgumentType.getString(context, ARGUMENT)));
            reply(player, "route " + RouteCodec.route(route));
        } catch (RuntimeException | AssertionError failure) {
            fail(player, "route", failure);
        }
        return 1;
    }

    private static int approach(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        try {
            String[] fields = StringArgumentType.getString(context, ARGUMENT).trim().split(" ");
            ServerLevel level = Objects.requireNonNull(context.getSource().getServer().getLevel(RouteCodec.level(fields[0])), "level " + fields[0]);
            SeamlessScenario.teleportToApproach(player, level, RouteCodec.position(fields[1]));
            reply(player, "approached");
        } catch (RuntimeException | AssertionError failure) {
            fail(player, "approach", failure);
        }
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        try {
            for (String portal : StringArgumentType.getString(context, ARGUMENT).trim().split(" ")) {
                SeamlessScenario.removePortal(player, context.getSource().getServer(), UUID.fromString(portal));
            }
            reply(player, "removed");
        } catch (RuntimeException | AssertionError failure) {
            fail(player, "remove", failure);
        }
        return 1;
    }

    private static int light(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        try {
            SeamlessWalkThrough.light(context.getSource().getServer(), player, RouteCodec.position(StringArgumentType.getString(context, ARGUMENT).trim()));
            reply(player, "lighting");
        } catch (RuntimeException | AssertionError failure) {
            fail(player, "light", failure);
        }
        return 1;
    }

    private static int lit(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        try {
            reply(player, "lit " + SeamlessWalkThrough.lit(context.getSource().getServer(),
                RouteCodec.position(StringArgumentType.getString(context, ARGUMENT).trim())));
        } catch (RuntimeException | AssertionError failure) {
            fail(player, "lit", failure);
        }
        return 1;
    }

    private static int centers(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        try {
            StringBuilder reply = new StringBuilder("centers");
            for (Vec3 center : SeamlessWalkThrough.centers(context.getSource().getServer(),
                RouteCodec.position(StringArgumentType.getString(context, ARGUMENT).trim()))) {
                reply.append(' ').append(center.x).append(',').append(center.y).append(',').append(center.z);
            }
            reply(player, reply.toString());
        } catch (RuntimeException | AssertionError failure) {
            fail(player, "centers", failure);
        }
        return 1;
    }

    private static int approachFrom(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        try {
            String[] fields = StringArgumentType.getString(context, ARGUMENT).trim().split(" ");
            ServerLevel level = Objects.requireNonNull(context.getSource().getServer().getLevel(RouteCodec.level(fields[0])), "level " + fields[0]);
            String[] position = fields[1].split(",");
            player.teleportTo(level, Double.parseDouble(position[0]), Double.parseDouble(position[1]), Double.parseDouble(position[2]), Set.of(),
                Float.parseFloat(fields[2]), 0.0F, false);
            reply(player, "approached");
        } catch (RuntimeException | AssertionError failure) {
            fail(player, "approachfrom", failure);
        }
        return 1;
    }

    private static void fail(ServerPlayer player, String action, Throwable failure) {
        LOGGER.error("wormholesqa {} failed", action, failure);
        reply(player, "error " + action + ": " + failure);
    }

    private static void reply(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("[whqa] " + text));
    }
}
