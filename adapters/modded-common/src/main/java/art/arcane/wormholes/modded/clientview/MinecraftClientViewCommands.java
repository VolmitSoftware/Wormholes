package art.arcane.wormholes.modded.clientview;

import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.wormholes.localization.ClientViewReplies;
import art.arcane.wormholes.modded.MinecraftMenuText;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamSession;
import art.arcane.optics.stream.ViewStreamSessionRegistry;
import art.arcane.optics.stream.ViewStreamSessionState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class MinecraftClientViewCommands {
    static final String PERMISSION = "wormholes.admin";

    private final WormholesModRuntime runtime;

    public MinecraftClientViewCommands(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wormholes").then(Commands.literal("clientview")
            .requires(source -> runtime.running() && runtime.access().permission(source, PERMISSION))
            .then(Commands.literal("status").executes(context -> status(context.getSource())))
            .then(Commands.literal("on").executes(context -> on(context.getSource())))
            .then(Commands.literal("off").executes(context -> off(context.getSource())))
            .then(Commands.literal("reset").then(Commands.argument("player", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder))
                .executes(context -> reset(context.getSource(), StringArgumentType.getString(context, "player")))))));
    }

    private int status(CommandSourceStack source) {
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> registry = runtime.clientViews().registry();
        if (registry == null) {
            return 0;
        }
        List<ClientViewReplies.Reply> replies = ClientViewReplies.status(registry.runtimeEnabled(), registry.options().enabled(),
            registry.stats(), playerId -> name(registry, playerId));
        LocalizationSnapshot snapshot = snapshot(source);
        for (ClientViewReplies.Reply reply : replies) {
            send(source, snapshot, reply);
        }
        return 1;
    }

    private int on(CommandSourceStack source) {
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> registry = runtime.clientViews().registry();
        if (registry == null) {
            return 0;
        }
        runtime.clientViews().runtimeEnabled(true);
        send(source, snapshot(source), ClientViewReplies.enabled(registry.options().enabled()));
        return 1;
    }

    private int off(CommandSourceStack source) {
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> registry = runtime.clientViews().registry();
        if (registry == null) {
            return 0;
        }
        runtime.clientViews().runtimeEnabled(false);
        send(source, snapshot(source), ClientViewReplies.disabled());
        return 1;
    }

    private int reset(CommandSourceStack source, String name) {
        ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> registry = runtime.clientViews().registry();
        if (registry == null) {
            return 0;
        }
        ServerPlayer player = runtime.server().getPlayerList().getPlayerByName(name);
        if (player == null) {
            send(source, snapshot(source), ClientViewReplies.playerMissing(name));
            return 0;
        }
        ViewStreamSession<MinecraftClientViewPeer, BlockState> session = registry.session(player.getUUID());
        boolean restarted = session != null && session.state() == ViewStreamSessionState.CLIENT_VIEW;
        if (restarted) {
            session.reset(ViewStreamMessage.ResetReason.TELEPORT);
        }
        send(source, snapshot(source), ClientViewReplies.reset(player.getGameProfile().name(), restarted));
        return restarted ? 1 : 0;
    }

    private LocalizationSnapshot snapshot(CommandSourceStack source) {
        return runtime.localization().snapshot(source.getPlayer());
    }

    private static String name(ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> registry, UUID playerId) {
        ViewStreamSession<MinecraftClientViewPeer, BlockState> session = registry.session(playerId);
        return session == null ? null : session.player().name();
    }

    private static void send(CommandSourceStack source, LocalizationSnapshot snapshot, ClientViewReplies.Reply reply) {
        source.sendSystemMessage(MinecraftMenuText.text(snapshot, reply.key(), reply.arguments()));
    }
}
