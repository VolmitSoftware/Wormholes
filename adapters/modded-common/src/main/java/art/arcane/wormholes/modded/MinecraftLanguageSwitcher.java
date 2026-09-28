package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.BukkitLanguageMessages;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.VolmitLocales;
import art.arcane.volmlib.util.plugin.ComponentText;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

final class MinecraftLanguageSwitcher {
    static final String COMMAND = "wormholes";
    static final String PLUGIN = "Wormholes";
    static final String ADMIN_PERMISSION = "wormholes.admin";
    private static final String SELF_PERMISSION = "wormholes.language.self";
    private static final int PAGE_SIZE = 9;
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final MinecraftDirectorMiniMenu.Theme THEME = MinecraftDirectorMiniMenu.Theme.WORMHOLES;

    private final WormholesModRuntime runtime;
    private final MinecraftLocalization localization;
    private final MinecraftLanguageEditor editor;

    MinecraftLanguageSwitcher(WormholesModRuntime runtime, MinecraftLocalization localization) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.localization = Objects.requireNonNull(localization, "localization");
        editor = new MinecraftLanguageEditor(runtime, localization,
            player -> command(player.createCommandSourceStack(), new String[]{"server"}));
    }

    MinecraftLanguageEditor editor() {
        return editor;
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(COMMAND).then(Commands.literal("language")
            .executes(context -> command(context.getSource(), new String[0]))
            .then(Commands.argument("arguments", StringArgumentType.greedyString())
                .suggests(this::suggest)
                .executes(context -> command(context.getSource(),
                    StringArgumentType.getString(context, "arguments").split(" "))))));
    }

    int command(CommandSourceStack source, String[] arguments) {
        if (localization.closed()) {
            message(source, BukkitLanguageMessages.STOPPING);
            return 1;
        }
        ServerPlayer player = source.getPlayer();
        if (arguments.length == 0) {
            showLanguageHome(source);
            return 1;
        }
        if (arguments.length >= 2 && arguments[0].equalsIgnoreCase("server") && arguments[1].equalsIgnoreCase("edit")) {
            if (!allowed(source, "server")) {
                return 1;
            }
            if (player == null) {
                message(source, BukkitLanguageMessages.EDITOR_PLAYER_ONLY);
            } else if (arguments.length > 3) {
                message(source, BukkitLanguageMessages.EDITOR_USAGE, MessageArgument.untrusted("command", COMMAND));
            } else {
                editor.open(player, arguments.length == 3 ? arguments[2] : null);
            }
            return 1;
        }
        String scope = arguments[0].toLowerCase(Locale.ROOT);
        if ((!scope.equals("self") && !scope.equals("server")) || arguments.length > 2) {
            message(source, BukkitLanguageMessages.SELECTION_USAGE, MessageArgument.untrusted("command", COMMAND));
            return 1;
        }
        if (!allowed(source, scope)) {
            return 1;
        }
        executeSelection(source, scope, arguments.length == 2 ? arguments[1] : null);
        return 1;
    }

    List<String> complete(CommandSourceStack source, String[] arguments) {
        if (localization.closed() || arguments.length == 0) {
            return List.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (arguments.length == 1) {
            if (canSelectSelf(source)) {
                candidates.add("self");
            }
            if (canSelectServer(source)) {
                candidates.add("server");
            }
        } else if (arguments.length == 2) {
            if (arguments[0].equalsIgnoreCase("self") && canSelectSelf(source)) {
                candidates.addAll(languages().availableLocales());
                candidates.add("reset");
            } else if (arguments[0].equalsIgnoreCase("server") && canSelectServer(source)) {
                candidates.addAll(languages().availableLocales());
                if (source.getPlayer() != null) {
                    candidates.add("edit");
                }
            }
        } else if (arguments.length == 3 && source.getPlayer() != null && arguments[0].equalsIgnoreCase("server")
            && arguments[1].equalsIgnoreCase("edit") && canSelectServer(source)) {
            candidates.addAll(languages().availableLocales());
        }
        return matching(candidates, arguments[arguments.length - 1]);
    }

    private CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String input = builder.getRemaining();
        SuggestionsBuilder token = builder.createOffset(builder.getStart() + input.lastIndexOf(' ') + 1);
        for (String candidate : complete(context.getSource(), input.split(" ", -1))) {
            token.suggest(candidate);
        }
        return token.buildFuture();
    }

    private void executeSelection(CommandSourceStack source, String scope, String value) {
        if (value == null) {
            showLocales(source, scope, 1);
        } else if (value.startsWith("page=")) {
            try {
                showLocales(source, scope, Integer.parseInt(value.substring(5)));
            } catch (NumberFormatException exception) {
                message(source, BukkitLanguageMessages.NUMERIC_PAGE);
            }
        } else {
            select(source, scope, value);
        }
    }

    private boolean allowed(CommandSourceStack source, String scope) {
        if (scope.equals("self")) {
            if (source.getPlayer() == null) {
                message(source, BukkitLanguageMessages.PERSONAL_PLAYER_ONLY);
                return false;
            }
            if (!permission(source, "volmit.language.self")) {
                message(source, BukkitLanguageMessages.PERSONAL_PERMISSION);
                return false;
            }
            if (!permission(source, SELF_PERMISSION)) {
                message(source, BukkitLanguageMessages.PLUGIN_PERSONAL_PERMISSION, MessageArgument.untrusted("plugin", PLUGIN));
                return false;
            }
            return true;
        }
        if (!permission(source, "volmit.language.admin") && !permission(source, ADMIN_PERMISSION)) {
            message(source, BukkitLanguageMessages.PLUGIN_SERVER_PERMISSION, MessageArgument.untrusted("plugin", PLUGIN));
            return false;
        }
        return true;
    }

    private boolean canSelectSelf(CommandSourceStack source) {
        return source.getPlayer() != null && permission(source, "volmit.language.self") && permission(source, SELF_PERMISSION);
    }

    private boolean canSelectServer(CommandSourceStack source) {
        return permission(source, "volmit.language.admin") || permission(source, ADMIN_PERMISSION);
    }

    private boolean permission(CommandSourceStack source, String node) {
        return runtime.access().permission(source, node);
    }

    private void showLocales(CommandSourceStack source, String scope, int requestedPage) {
        ServerPlayer player = source.getPlayer();
        List<String> locales = languages().availableLocales();
        MinecraftDirectorMiniMenu.ContentPage page = MinecraftDirectorMiniMenu.paginate(locales.size(), requestedPage, PAGE_SIZE);
        String base = "/" + COMMAND + " language " + scope;
        String pluginBase = "/" + COMMAND + " language";
        List<String> lines = new ArrayList<>(PAGE_SIZE + 6);
        lines.add(MinecraftDirectorMiniMenu.banner(base, THEME));
        lines.add(MinecraftDirectorMiniMenu.backLink(pluginBase, THEME, resolver(player)));
        String current = selectedLocale(source, scope);
        if (page.page() == 1) {
            if (canSelectSelf(source)) {
                lines.add(link(source, localized(player, BukkitLanguageMessages.YOUR_LANGUAGE), pluginBase + " self",
                    localized(player, BukkitLanguageMessages.YOUR_DESCRIPTION)));
            }
            if (canSelectServer(source)) {
                lines.add(link(source, localized(player, BukkitLanguageMessages.SERVER_DEFAULT), pluginBase + " server",
                    localized(player, BukkitLanguageMessages.SERVER_DESCRIPTION)));
            }
        }
        for (int index = page.startIndex(); index < page.endIndex(); index++) {
            String locale = locales.get(index);
            String name = VolmitLocales.displayName(locale).orElse(locale);
            lines.add(languageLink(source, locale, name, locale.equalsIgnoreCase(current), base + " " + locale));
        }
        if (page.page() == 1 && scope.equals("self") && player != null && languages().playerLocale(player.getUUID()).isPresent()) {
            lines.add(link(source, localized(player, BukkitLanguageMessages.USE_SERVER_DEFAULT), base + " reset",
                localized(player, BukkitLanguageMessages.REMOVE_PERSONAL_DESCRIPTION)));
        }
        lines.add(MinecraftDirectorMiniMenu.paginationBar(page, base, THEME, resolver(player)));
        MinecraftDirectorMiniMenu.deliver(source, lines);
    }

    private void showLanguageHome(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        String command = "/" + COMMAND + " language";
        String serverLocale = selectedLocale(source, "server");
        ArrayList<String> entries = new ArrayList<>();
        if (canSelectSelf(source) && player != null) {
            String personalLocale = selectedLocale(source, "self");
            entries.add(link(source, localized(player, BukkitLanguageMessages.YOUR_LANGUAGE), command + " self",
                localized(player, BukkitLanguageMessages.CURRENT, MessageArgs.builder().untrusted("locale", personalLocale).build())));
            if (canSelectServer(source)) {
                String current = sameLocale(serverLocale, personalLocale)
                    ? localized(player, BukkitLanguageMessages.CURRENT, MessageArgs.builder().untrusted("locale", serverLocale).build())
                    : localized(player, BukkitLanguageMessages.CURRENT_WITH_PERSONAL, MessageArgs.builder()
                    .untrusted("locale", serverLocale)
                    .untrusted("personal", personalLocale)
                    .build());
                entries.add(link(source, localized(player, BukkitLanguageMessages.SERVER_DEFAULT), command + " server", current));
            }
            entries.add(link(source, localized(player, BukkitLanguageMessages.RESET_YOUR_LANGUAGE), command + " self reset",
                localized(player, BukkitLanguageMessages.RESET_DESCRIPTION)));
        } else if (canSelectServer(source)) {
            entries.add(link(source, localized(player, BukkitLanguageMessages.SERVER_DEFAULT), command + " server",
                localized(player, BukkitLanguageMessages.CURRENT, MessageArgs.builder().untrusted("locale", serverLocale).build())));
        }
        if (player != null && canSelectServer(source)) {
            entries.add(link(source, localized(player, BukkitLanguageMessages.EDIT_MESSAGES), command + " server edit",
                localized(player, BukkitLanguageMessages.EDIT_DESCRIPTION)));
        }
        MinecraftDirectorMiniMenu.deliverContent(source, new MinecraftDirectorMiniMenu.ContentMenu(
            command, command, "/" + COMMAND, entries,
            styled(localized(player, BukkitLanguageMessages.NO_CONTROLS), THEME.muted()),
            1, Math.max(1, entries.size())), THEME, resolver(player));
    }

    private void select(CommandSourceStack source, String scope, String locale) {
        if (scope.equals("server") && locale.equalsIgnoreCase("reset")) {
            message(source, BukkitLanguageMessages.SERVER_LOCALE_REQUIRED);
            return;
        }
        if (!locale.equalsIgnoreCase("reset") && languages().availableLocales().stream()
            .noneMatch(candidate -> sameLocale(candidate, locale))) {
            message(source, BukkitLanguageMessages.UNAVAILABLE_FOR_ALL, MessageArgument.untrusted("locale", locale));
            return;
        }
        message(source, BukkitLanguageMessages.PREPARING,
            MessageArgument.untrusted("locale", locale),
            MessageArgument.untrusted("target", PLUGIN));
        CompletableFuture<String> pending = scope.equals("self")
            ? localization.selectPlayer(source.getPlayer(), locale)
            : localization.selectServer(locale).thenApply(ignored -> languages().defaultLocale());
        pending.whenComplete((appliedLocale, failure) -> {
            if (failure != null) {
                LOGGER.error("Unable to select {} for {}", locale, PLUGIN, failure);
            }
            reply(source, () -> selectionFeedback(source, scope, locale, appliedLocale, failure));
        });
    }

    private void selectionFeedback(CommandSourceStack source, String scope, String requested, String applied, Throwable failure) {
        if (failure != null) {
            message(source, BukkitLanguageMessages.SAVE_FAILED, MessageArgument.untrusted("plugin", PLUGIN));
        } else if (!sameLocale(requested, applied)) {
            message(source, BukkitLanguageMessages.ENGLISH_FALLBACK,
                MessageArgument.untrusted("plugin", PLUGIN),
                MessageArgument.untrusted("locale", requested));
        } else if (scope.equals("self") && applied.equalsIgnoreCase("reset")) {
            message(source, BukkitLanguageMessages.SERVER_DEFAULT_SELECTED, MessageArgument.untrusted("plugin", PLUGIN));
        } else if (scope.equals("self")) {
            message(source, BukkitLanguageMessages.PERSONAL_SELECTED,
                MessageArgument.untrusted("plugin", PLUGIN),
                MessageArgument.untrusted("locale", applied));
        } else {
            message(source, BukkitLanguageMessages.SERVER_SELECTED,
                MessageArgument.untrusted("plugin", PLUGIN),
                MessageArgument.untrusted("locale", applied));
        }
    }

    private void reply(CommandSourceStack source, Runnable message) {
        if (localization.closed()) {
            return;
        }
        ServerPlayer player = source.getPlayer();
        runtime.schedule(() -> {
            if (player == null || !player.hasDisconnected()) {
                message.run();
            }
        }, 1L);
    }

    private String selectedLocale(CommandSourceStack source, String scope) {
        ServerPlayer player = source.getPlayer();
        return scope.equals("self") && player != null ? languages().effectiveLocale(player.getUUID()) : languages().defaultLocale();
    }

    private PluginLanguageService languages() {
        return localization.languages();
    }

    private void message(CommandSourceStack source, TextKey key, MessageArgument... arguments) {
        MessageArgs.Builder builder = MessageArgs.builder();
        for (MessageArgument argument : arguments) {
            builder.add(argument);
        }
        MinecraftDirectorMiniMenu.send(source, MinecraftLanguageEditor.localized(languages(), source.getPlayer(), key, builder.build())
            .colorIfAbsent(THEME.description()));
    }

    private String link(CommandSourceStack source, String label, String command, String hover) {
        if (source.getPlayer() == null) {
            return styled(label + ": " + command, THEME.description());
        }
        ComponentText content = ComponentText.markup(
            "<gradient:" + THEME.primaryLeft() + ":" + THEME.primaryRight() + ">"
                + MinecraftDirectorMiniMenu.escapeText(label) + "</gradient>"
                + "<" + THEME.muted() + "> - </" + THEME.muted() + ">"
                + "<" + THEME.description() + ">" + MinecraftDirectorMiniMenu.escapeText(hover)
                + "</" + THEME.description() + ">"
        );
        return entry(content.clickRunCommand(command).hover(commandHover(source.getPlayer(), label, hover, command)));
    }

    private String languageLink(CommandSourceStack source, String locale, String name, boolean selected, String command) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            String selectedPrefix = selected ? "[" + localized(null, BukkitLanguageMessages.SELECTED) + "] " : "";
            return styled(selectedPrefix + locale + " - " + name + ": " + command, THEME.description());
        }
        ComponentText content = ComponentText.markup(
            (selected ? "<green>✔</green> " : "<dark_gray>•</dark_gray> ")
                + "<gradient:" + THEME.primaryLeft() + ":" + THEME.primaryRight() + ">"
                + MinecraftDirectorMiniMenu.escapeText(locale) + "</gradient>"
                + "<" + THEME.muted() + "> - </" + THEME.muted() + ">"
                + "<" + THEME.description() + ">" + MinecraftDirectorMiniMenu.escapeText(name)
                + "</" + THEME.description() + ">"
        );
        return entry(content.clickRunCommand(command).hover(commandHover(player,
            locale + " - " + name, localized(player, BukkitLanguageMessages.SELECT_DESCRIPTION), command)));
    }

    private String localized(ServerPlayer player, TextKey key) {
        return localized(player, key, MessageArgs.empty());
    }

    private String localized(ServerPlayer player, TextKey key, MessageArgs arguments) {
        return ComponentText.markup(localization.directorText(player, key, arguments)).plain();
    }

    private Function<TextKey, String> resolver(ServerPlayer player) {
        return key -> localization.directorText(player, key, MessageArgs.empty());
    }

    private String entry(ComponentText content) {
        return ComponentText.markup("<" + THEME.muted() + ">⇀</" + THEME.muted() + "> ").append(content).miniMessage();
    }

    private ComponentText commandHover(ServerPlayer player, String title, String description, String command) {
        return ComponentText.markup(
            "<" + THEME.primaryRight() + ">" + MinecraftDirectorMiniMenu.escapeText(title)
                + "</" + THEME.primaryRight() + "><reset>\n"
                + "<" + THEME.description() + ">✎ <font:minecraft:uniform>"
                + MinecraftDirectorMiniMenu.escapeText(description) + "</font></" + THEME.description() + "><reset>\n"
                + "<" + THEME.optional() + ">✒ <font:minecraft:uniform>"
                + MinecraftDirectorMiniMenu.escapeText(localized(player, BukkitLanguageMessages.COMMAND,
                MessageArgs.builder().untrusted("command", command).build()))
                + "</font></" + THEME.optional() + ">"
        );
    }

    private static String styled(String text, String color) {
        return "<" + THEME.muted() + ">⇀ </" + THEME.muted() + "><" + color + ">"
            + MinecraftDirectorMiniMenu.escapeText(text) + "</" + color + ">";
    }

    private static List<String> matching(Collection<String> candidates, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return candidates.stream().filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private static boolean sameLocale(String first, String second) {
        return first.replace('-', '_').equalsIgnoreCase(second.replace('-', '_'));
    }
}
