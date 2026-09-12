package art.arcane.wormholes.atlas;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.AtlasConfig;
import art.arcane.wormholes.localization.AtlasMessages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The player-facing {@code /atlas} root command. Plain Bukkit rather than a Director tree: Director's
 * help theme is admin-facing, and this command belongs to players.
 */
public final class CommandAtlas implements CommandExecutor, TabCompleter {
    public static final String NAME = "atlas";
    public static final String PERMISSION = "wormholes.atlas";
    private static final String GUIDE_OFF = "off";
    private static final List<String> SUBCOMMANDS = List.of("favorites", "recents", "guide");

    private final AtlasService service;
    private final AtlasMenu menu;

    public CommandAtlas(AtlasService service, AtlasMenu menu) {
        this.service = Objects.requireNonNull(service, "service");
        this.menu = Objects.requireNonNull(menu, "menu");
    }

    public enum Subcommand {
        OPEN,
        FAVORITES,
        RECENTS,
        GUIDE,
        GUIDE_OFF,
        USAGE
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        return execute(sender, args);
    }

    public boolean execute(CommandSender sender, String[] args) {
        AtlasConfig settings = config();
        TextKey refusal = refusal(sender, settings.enabled);
        if (refusal != null) {
            AtlasText.send(sender, refusal);
            return true;
        }
        Player player = (Player) sender;
        switch (parse(args)) {
            case OPEN -> menu.open(player, AtlasModel.Filter.ALL);
            case FAVORITES -> menu.open(player, AtlasModel.Filter.FAVORITES);
            case RECENTS -> menu.open(player, AtlasModel.Filter.RECENTS);
            case GUIDE -> guide(player, guideTarget(args));
            case GUIDE_OFF -> {
                service.setGuideTarget(player, null);
                AtlasText.send(player, AtlasMessages.GUIDE_CLEARED);
            }
            case USAGE -> AtlasText.sendLines(sender, AtlasMessages.USAGE, MessageArgs.empty());
        }
        return true;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        if (refusal(sender, config().enabled) != null) {
            return List.of();
        }
        return completions(args, portalNames((Player) sender));
    }

    /** The message to refuse with, or null when the command may run. */
    public static TextKey refusal(CommandSender sender, boolean atlasEnabled) {
        if (sender == null || !sender.hasPermission(PERMISSION)) {
            return AtlasMessages.NO_PERMISSION;
        }
        if (!(sender instanceof Player)) {
            return AtlasMessages.ONLY_PLAYERS;
        }
        return atlasEnabled ? null : AtlasMessages.DISABLED;
    }

    public static Subcommand parse(String[] args) {
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

    /** Everything after {@code guide}, joined so portal names with spaces survive. */
    public static String guideTarget(String[] args) {
        if (args == null || args.length < 2) {
            return "";
        }
        return String.join(" ", List.of(args).subList(1, args.length));
    }

    public static List<String> completions(String[] args, List<String> portalNames) {
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

    private void guide(Player player, String name) {
        AtlasModel.Row target = null;
        for (AtlasModel.Row row : service.candidates(player)) {
            if (row.name().equalsIgnoreCase(name)) {
                target = row;
                break;
            }
        }
        if (target == null) {
            AtlasText.send(player, AtlasMessages.GUIDE_UNKNOWN, AtlasText.args("portal", name));
            return;
        }
        service.setGuideTarget(player, target.portalId());
        AtlasText.send(player, AtlasMessages.GUIDE_SET, AtlasText.args("portal", target.name()));
    }

    private List<String> portalNames(Player player) {
        return service.candidates(player).stream().map(AtlasModel.Row::name).toList();
    }

    private static List<String> matching(List<String> candidates, String partial) {
        String prefix = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }

    private static AtlasConfig config() {
        return Wormholes.settings == null ? new AtlasConfig() : Wormholes.settings.getAtlas();
    }
}
