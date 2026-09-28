package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.atlas.AtlasModel;
import art.arcane.wormholes.localization.AtlasMessages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class MinecraftAtlasCommand {
    static final String NAME = "atlas";
    static final String ALIAS = "portals";
    static final String PERMISSION = "wormholes.atlas";
    private static final String ARGUMENTS = "arguments";
    private static final String GUIDE_OFF = "off";
    private static final List<String> SUBCOMMANDS = List.of("favorites", "recents", "guide");

    private final WormholesModRuntime runtime;
    private final MinecraftAtlasService service;

    MinecraftAtlasCommand(WormholesModRuntime runtime, MinecraftAtlasService service) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.service = Objects.requireNonNull(service, "service");
    }

    enum Subcommand {
        OPEN,
        FAVORITES,
        RECENTS,
        GUIDE,
        GUIDE_OFF,
        USAGE
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> command = dispatcher.register(Commands.literal(NAME)
            .executes(context -> execute(context.getSource(), new String[0]))
            .then(Commands.argument(ARGUMENTS, StringArgumentType.greedyString())
                .suggests(this::suggest)
                .executes(context -> execute(context.getSource(), split(StringArgumentType.getString(context, ARGUMENTS))))));
        dispatcher.register(Commands.literal(ALIAS).executes(context -> execute(context.getSource(), new String[0])).redirect(command));
    }

    static Subcommand parse(String[] args) {
        if (args == null || args.length == 0) {
            return Subcommand.OPEN;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            return switch (first) {
                case "favorites" -> Subcommand.FAVORITES;
                case "recents" -> Subcommand.RECENTS;
                default -> Subcommand.USAGE;
            };
        }
        if (!"guide".equals(first)) {
            return Subcommand.USAGE;
        }
        return args.length == 2 && GUIDE_OFF.equalsIgnoreCase(args[1]) ? Subcommand.GUIDE_OFF : Subcommand.GUIDE;
    }

    static String guideTarget(String[] args) {
        if (args == null || args.length < 2) {
            return "";
        }
        return String.join(" ", List.of(args).subList(1, args.length));
    }

    static List<String> completions(String[] args, List<String> portalNames) {
        if (args == null || args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            return matching(SUBCOMMANDS, args[0]);
        }
        if (args.length != 2 || !"guide".equalsIgnoreCase(args[0])) {
            return List.of();
        }
        List<String> targets = new ArrayList<>(portalNames.size() + 1);
        targets.add(GUIDE_OFF);
        targets.addAll(portalNames);
        return matching(targets, args[1]);
    }

    private int execute(CommandSourceStack source, String[] args) {
        TextKey refusal = refusal(source);
        if (refusal != null) {
            send(source, refusal);
            return 1;
        }
        ServerPlayer player = Objects.requireNonNull(source.getPlayer(), "player");
        switch (parse(args)) {
            case OPEN -> service.open(player, AtlasModel.Filter.ALL);
            case FAVORITES -> service.open(player, AtlasModel.Filter.FAVORITES);
            case RECENTS -> service.open(player, AtlasModel.Filter.RECENTS);
            case GUIDE -> guide(player, guideTarget(args));
            case GUIDE_OFF -> service.withState(player, state -> {
                service.setGuideTarget(player, state, null);
                service.send(player, AtlasMessages.GUIDE_CLEARED, MessageArgs.empty());
            });
            case USAGE -> {
                for (Component line : MinecraftMenuText.lines(MinecraftLocalization.forPlayer(player).snapshot(player), AtlasMessages.USAGE, Map.of())) {
                    player.sendSystemMessage(line);
                }
            }
        }
        return 1;
    }

    private TextKey refusal(CommandSourceStack source) {
        if (!runtime.access().permission(source, PERMISSION)) {
            return AtlasMessages.NO_PERMISSION;
        }
        if (source.getPlayer() == null) {
            return AtlasMessages.ONLY_PLAYERS;
        }
        return service.enabled() ? null : AtlasMessages.DISABLED;
    }

    private CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null || refusal(context.getSource()) != null) {
            return builder.buildFuture();
        }
        String remaining = builder.getRemaining();
        List<String> names = new ArrayList<>();
        for (AtlasModel.Row row : service.candidates(player)) {
            names.add(row.name());
        }
        SuggestionsBuilder token = builder.createOffset(builder.getStart() + remaining.lastIndexOf(' ') + 1);
        for (String completion : completions(split(remaining), names)) {
            token.suggest(completion);
        }
        return token.buildFuture();
    }

    private void guide(ServerPlayer player, String name) {
        service.withState(player, state -> {
            AtlasModel.Row target = null;
            for (AtlasModel.Row row : service.candidates(player)) {
                if (row.name().equalsIgnoreCase(name)) {
                    target = row;
                    break;
                }
            }
            if (target == null) {
                service.send(player, AtlasMessages.GUIDE_UNKNOWN, MinecraftPortalText.arguments("portal", name));
                return;
            }
            service.setGuideTarget(player, state, target.portalId());
            service.send(player, AtlasMessages.GUIDE_SET, MinecraftPortalText.arguments("portal", target.name()));
        });
    }

    private void send(CommandSourceStack source, TextKey key) {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            service.send(player, key, MessageArgs.empty());
            return;
        }
        source.sendSystemMessage(runtime.localization().text(null, key, Map.of()));
    }

    private static String[] split(String arguments) {
        return arguments.split(" ", -1);
    }

    private static List<String> matching(List<String> candidates, String partial) {
        String prefix = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>(candidates.size());
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(candidate);
            }
        }
        return matches;
    }
}
