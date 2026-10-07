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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
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
            .then(Commands.literal("remove").then(Commands.argument(ARGUMENT, StringArgumentType.greedyString()).executes(SeamlessQaCommand::remove))));
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

    private static void fail(ServerPlayer player, String action, Throwable failure) {
        LOGGER.error("wormholesqa {} failed", action, failure);
        reply(player, "error " + action + ": " + failure);
    }

    private static void reply(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("[whqa] " + text));
    }
}
