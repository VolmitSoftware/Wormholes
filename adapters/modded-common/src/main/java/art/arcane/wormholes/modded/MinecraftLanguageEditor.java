package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.format.ColorFormatter;
import art.arcane.volmlib.util.localization.BukkitLanguageMessages;
import art.arcane.volmlib.util.localization.LinesValue;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.MessageArgumentKind;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.MessageValue;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.PluralValue;
import art.arcane.volmlib.util.localization.ResolvedText;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.VolmitLocales;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.wormholes.localization.WormholesMessageRenderer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class MinecraftLanguageEditor implements AutoCloseable {
    static final int PAGE_SIZE = 45;
    static final int CATEGORY_PAGE_SIZE = 16;
    static final int SIZE = 54;
    static final int BACK = 45;
    static final int PREVIOUS = 48;
    static final int SEARCH = 49;
    static final int NEXT = 50;
    static final int CLOSE = 53;
    private static final int MAXIMUM_INPUT_LENGTH = 512;
    private static final long PROMPT_TICKS = 1200L;
    private static final List<String> PLURAL_FORMS = List.of("zero", "one", "two", "few", "many", "other");
    private static final String COLOR_CODES = "0123456789abcdefklmnor";
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final MinecraftDirectorMiniMenu.Theme THEME = MinecraftDirectorMiniMenu.Theme.WORMHOLES;

    private final WormholesModRuntime runtime;
    private final MinecraftLocalization localization;
    private final Consumer<ServerPlayer> back;
    private final Map<UUID, View> views = new ConcurrentHashMap<>();
    private final Map<UUID, EditorMenu> openMenus = new ConcurrentHashMap<>();
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<PluginLanguageEditor.Document>> pending = new ConcurrentHashMap<>();
    private PluginLanguageEditor editor;
    private volatile boolean closed = true;

    MinecraftLanguageEditor(WormholesModRuntime runtime, MinecraftLocalization localization, Consumer<ServerPlayer> back) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.localization = Objects.requireNonNull(localization, "localization");
        this.back = Objects.requireNonNull(back, "back");
    }

    void start(PluginLanguageEditor editor) {
        this.editor = Objects.requireNonNull(editor, "editor");
        closed = false;
    }

    void open(ServerPlayer player, String locale) {
        if (!allowed(player)) {
            return;
        }
        cancel(player.getUUID());
        if (locale == null) {
            show(player, new View(null, null, null, "", 1, null));
        } else {
            load(player, new View(locale, null, null, "", 1, null));
        }
    }

    boolean chat(ServerPlayer player, String input) {
        UUID playerId = player.getUUID();
        Prompt prompt = prompts.remove(playerId);
        if (prompt == null) {
            return false;
        }
        if (!runtime.schedule(() -> submitted(player, prompt, input), 1L)) {
            views.remove(playerId, prompt.view());
        }
        return true;
    }

    void opened(ServerPlayer player, AbstractContainerMenu menu) {
        if (menu == player.inventoryMenu || menu instanceof EditorMenu editorMenu && editorMenu.owner == this) {
            return;
        }
        cancel(player.getUUID());
    }

    void disconnected(ServerPlayer player) {
        openMenus.remove(player.getUUID());
        cancel(player.getUUID());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (CompletableFuture<PluginLanguageEditor.Document> future : pending.values()) {
            future.cancel(true);
        }
        pending.clear();
        prompts.clear();
        if (editor != null) {
            editor.close();
            editor = null;
        }
        for (EditorMenu menu : List.copyOf(openMenus.values())) {
            if (menu.viewer.containerMenu == menu) {
                menu.viewer.closeContainer();
            }
        }
        openMenus.clear();
        views.clear();
    }

    static ComponentText localized(PluginLanguageService languages, ServerPlayer player, TextKey key, MessageArgs supplied) {
        String template;
        MessageArgs resolvedArguments;
        try {
            ResolvedText resolved = languages.snapshot(player == null ? null : player.getUUID()).resolve(key, supplied);
            template = resolved.template();
            resolvedArguments = resolved.arguments();
        } catch (RuntimeException failure) {
            template = key.english();
            resolvedArguments = supplied;
        }
        template = ComponentText.normalizeMarkup(template);
        for (MessageArgument argument : resolvedArguments.arguments().values()) {
            String value = String.valueOf(argument.value());
            if (argument.kind() == MessageArgumentKind.UNTRUSTED) {
                value = MinecraftDirectorMiniMenu.escapeText(ColorFormatter.stripColor(value));
            } else {
                value = ComponentText.normalizeMarkup(value);
            }
            template = template.replace("{" + argument.name() + "}", value);
        }
        return ComponentText.component(WormholesMessageRenderer.markup(template));
    }

    static ComponentText localeTitle(String locale, String name, boolean active) {
        return ComponentText.markup(
            (active ? "<green>✔</green> " : "<dark_gray>•</dark_gray> ")
                + "<white>" + MinecraftDirectorMiniMenu.escapeText(locale) + "</white>"
                + " <dark_gray>—</dark_gray> "
                + "<gray>" + MinecraftDirectorMiniMenu.escapeText(name) + "</gray>"
        );
    }

    static ComponentText categoryTitle(String name) {
        return ComponentText.markup("&d" + MinecraftDirectorMiniMenu.escapeText(name) + "&r");
    }

    static Set<Integer> navigationSlots() {
        return Set.of(BACK, PREVIOUS, SEARCH, NEXT, CLOSE);
    }

    static List<Integer> categorySlots(int count) {
        int bounded = Math.max(0, Math.min(count, CATEGORY_PAGE_SIZE));
        if (bounded == 0) {
            return List.of();
        }
        int rows = (bounded + 6) / 7;
        int firstRow = (5 - rows) / 2;
        ArrayList<Integer> slots = new ArrayList<>(bounded);
        int remaining = bounded;
        for (int row = 0; row < rows; row++) {
            int rowsLeft = rows - row;
            int rowSize = (remaining + rowsLeft - 1) / rowsLeft;
            int firstColumn = (9 - rowSize) / 2;
            for (int column = firstColumn; column < firstColumn + rowSize; column++) {
                slots.add((firstRow + row) * 9 + column);
            }
            remaining -= rowSize;
        }
        return List.copyOf(slots);
    }

    static List<MessageKey> matchingKeys(PluginLanguageEditor.Document document, String group, String filter) {
        String query = filter.toLowerCase(Locale.ROOT);
        List<MessageKey> keys = new ArrayList<>();
        for (MessageKey key : document.snapshot().catalog().keys()) {
            if (group != null && !group(key.id()).equals(group)) {
                continue;
            }
            if (query.isEmpty() || key.id().toLowerCase(Locale.ROOT).contains(query)
                || rawValue(document.snapshot().value(key), null).toLowerCase(Locale.ROOT).contains(query)) {
                keys.add(key);
            }
        }
        keys.sort(Comparator.comparing(MessageKey::id));
        return keys;
    }

    static List<String> groups(PluginLanguageEditor.Document document) {
        Set<String> groups = new LinkedHashSet<>();
        for (MessageKey key : document.snapshot().catalog().keys()) {
            groups.add(group(key.id()));
        }
        ArrayList<String> sorted = new ArrayList<>(groups);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(sorted);
    }

    static String group(String messageId) {
        int separator = messageId.indexOf('.');
        return separator < 0 ? messageId : messageId.substring(0, separator);
    }

    static String groupName(String group) {
        if (group.equalsIgnoreCase("gui") || group.equalsIgnoreCase("hud") || group.equalsIgnoreCase("api")) {
            return group.toUpperCase(Locale.ROOT);
        }
        String normalized = group.replace('_', ' ').replace('-', ' ').trim();
        return normalized.isEmpty() ? group : Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    static Item groupMaterial(String group) {
        return switch (group.toLowerCase(Locale.ROOT)) {
            case "command" -> Items.COMMAND_BLOCK;
            case "config", "configuration" -> Items.COMPARATOR;
            case "debug", "diagnostics" -> Items.SPYGLASS;
            case "director", "help" -> Items.WRITABLE_BOOK;
            case "gui", "menu" -> Items.CHEST;
            case "hud", "presentation" -> Items.NAME_TAG;
            case "integration" -> Items.ENDER_CHEST;
            case "portal" -> Items.OBSIDIAN;
            case "runtime", "system" -> Items.REDSTONE_TORCH;
            default -> Items.PAPER;
        };
    }

    static String variableNames(MessageKey key) {
        return String.join(" ", key.placeholders().stream().sorted().map(name -> "{" + name + "}").toList());
    }

    static MessageValue replacement(MessageValue current, String form, String value) {
        if (current instanceof TextValue) {
            return new TextValue(value);
        }
        if (current instanceof LinesValue lines) {
            List<String> updated = new ArrayList<>(lines.lines());
            updated.set(Integer.parseInt(Objects.requireNonNull(form, "line")), value);
            return new LinesValue(updated);
        }
        PluralValue plural = (PluralValue) current;
        Map<String, String> forms = new LinkedHashMap<>(plural.forms());
        forms.put(Objects.requireNonNull(form, "plural form"), value);
        return new PluralValue(forms);
    }

    static String rawValue(MessageValue value, String form) {
        if (value instanceof TextValue text) {
            return text.template();
        }
        if (value instanceof LinesValue lines) {
            return form == null ? String.join("\n", lines.lines()) : lines.lines().get(Integer.parseInt(form));
        }
        PluralValue plural = (PluralValue) value;
        if (form != null) {
            return plural.forms().getOrDefault(form, plural.forms().get("other"));
        }
        return String.join("\n", plural.forms().entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue()).toList());
    }

    static String decodeInput(String input) {
        StringBuilder result = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (current == '\\' && index + 1 < input.length()) {
                char next = input.charAt(index + 1);
                if (next == 'n' || next == '\\') {
                    result.append(next == 'n' ? '\n' : '\\');
                    index++;
                    continue;
                }
            }
            result.append(current);
        }
        return result.toString();
    }

    static List<String> preview(String text, int width, int maximumLines, String empty) {
        String rendered = ComponentText.markup(text).legacy();
        if (rendered.isEmpty()) {
            return List.of(empty);
        }
        int safeWidth = Math.max(1, width);
        int safeMaximumLines = Math.max(1, maximumLines);
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int visible = 0;
        for (int index = 0; index < rendered.length(); index++) {
            char character = rendered.charAt(index);
            if (character == '§' && index + 1 < rendered.length()) {
                current.append(character).append(rendered.charAt(++index));
                continue;
            }
            if (character == '\r') {
                continue;
            }
            if (character == '\n' || visible == safeWidth) {
                lines.add(current.toString());
                if (lines.size() == safeMaximumLines) {
                    lines.set(safeMaximumLines - 1, lines.get(safeMaximumLines - 1) + "§8...");
                    return List.copyOf(lines);
                }
                current = new StringBuilder(lastColors(current.toString()));
                visible = 0;
                if (character == '\n') {
                    continue;
                }
            }
            current.append(character);
            visible++;
        }
        if (visible > 0 || lines.isEmpty()) {
            lines.add(current.toString());
        }
        return List.copyOf(lines);
    }

    static String lastColors(String input) {
        String result = "";
        int length = input.length();
        for (int index = length - 1; index > -1; index--) {
            if (input.charAt(index) != '§' || index >= length - 1) {
                continue;
            }
            if (index > 11 && input.charAt(index - 12) == '§'
                && (input.charAt(index - 11) == 'x' || input.charAt(index - 11) == 'X')) {
                String color = input.substring(index - 12, index + 2);
                if (hexColor(color)) {
                    return color + result;
                }
            }
            char code = input.charAt(index + 1);
            if (COLOR_CODES.indexOf(code) < 0) {
                continue;
            }
            result = "§" + code + result;
            if (code <= 'f' || code == 'r') {
                return result;
            }
        }
        return result;
    }

    static String clip(String value) {
        return value.length() <= 512 ? value : value.substring(0, 512) + "...";
    }

    private static boolean hexColor(String color) {
        if (color.length() != 14) {
            return false;
        }
        for (int index = 2; index < 14; index += 2) {
            if (color.charAt(index) != '§' || Character.digit(color.charAt(index + 1), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private void routeClick(EditorMenu menu, int slot, ContainerInput input) {
        if (input == ContainerInput.QUICK_CRAFT || slot < 0 || slot >= SIZE) {
            return;
        }
        ServerPlayer player = menu.viewer;
        views.put(player.getUUID(), menu.view);
        Runnable routing = () -> {
            if (player.hasDisconnected() || player.containerMenu != menu) {
                return;
            }
            views.put(player.getUUID(), menu.view);
            click(player, menu.view, slot);
        };
        if (!runtime.schedule(routing, 1L)) {
            routing.run();
        }
    }

    private void menuClosed(EditorMenu menu) {
        UUID playerId = menu.viewer.getUUID();
        openMenus.remove(playerId, menu);
        if (prompts.containsKey(playerId)) {
            return;
        }
        if (pending.containsKey(playerId)) {
            cancel(playerId);
        } else {
            views.remove(playerId, menu.view);
        }
    }

    private void click(ServerPlayer player, View view, int slot) {
        if (!allowed(player)) {
            return;
        }
        if (slot == CLOSE) {
            cancel(player.getUUID());
            player.closeContainer();
        } else if (slot == BACK) {
            back(player, view);
        } else if (slot == PREVIOUS || slot == NEXT) {
            show(player, new View(view.locale(), view.document(), view.group(), view.filter(),
                view.page() + (slot == NEXT ? 1 : -1), view.key()));
        } else if (slot == SEARCH && view.document() != null && view.group() == null
            && view.key() == null && view.filter().isEmpty()) {
            beginPrompt(player, new Prompt(view, null, null, null));
        } else if (slot == SEARCH && !view.filter().isEmpty()) {
            show(player, new View(view.locale(), view.document(), view.group(), "", 1, null));
        } else if (slot < PAGE_SIZE) {
            selectEntry(player, view, slot);
        }
    }

    private void back(ServerPlayer player, View view) {
        if (view.key() != null) {
            show(player, new View(view.locale(), view.document(), view.group(), view.filter(),
                Math.max(0, keys(view).indexOf(view.key())) / PAGE_SIZE + 1, null));
        } else if (view.group() != null) {
            show(player, new View(view.locale(), view.document(), null, "", 1, null));
        } else if (!view.filter().isEmpty()) {
            show(player, new View(view.locale(), view.document(), null, "", 1, null));
        } else if (view.locale() != null) {
            show(player, new View(null, null, null, "", 1, null));
        } else {
            cancel(player.getUUID());
            player.closeContainer();
            back.accept(player);
        }
    }

    private void selectEntry(ServerPlayer player, View view, int slot) {
        int ordinal = contentOrdinal(view, slot);
        if (ordinal < 0) {
            return;
        }
        int index = (view.page() - 1) * pageSize(view) + ordinal;
        if (view.locale() == null) {
            List<String> locales = localization.languages().availableLocales();
            if (index < locales.size()) {
                load(player, new View(locales.get(index), null, null, "", 1, null));
            }
            return;
        }
        if (view.group() == null && view.filter().isEmpty()) {
            List<String> groups = groups(view.document());
            if (index < groups.size()) {
                show(player, new View(view.locale(), view.document(), groups.get(index), "", 1, null));
            }
            return;
        }
        if (view.key() != null) {
            List<String> forms = parts(view);
            if (index < forms.size()) {
                beginPrompt(player, new Prompt(view, view.key(), forms.get(index), view.document().snapshot().value(view.key())));
            }
            return;
        }
        List<MessageKey> keys = keys(view);
        if (index >= keys.size()) {
            return;
        }
        MessageKey key = keys.get(index);
        MessageValue value = view.document().snapshot().value(key);
        if (value instanceof PluralValue || value instanceof LinesValue) {
            show(player, new View(view.locale(), view.document(), view.group(), view.filter(), 1, key));
        } else {
            beginPrompt(player, new Prompt(view, key, null, value));
        }
    }

    private void load(ServerPlayer player, View view) {
        if (!allowed(player)) {
            return;
        }
        views.remove(player.getUUID());
        prompts.remove(player.getUUID());
        message(player, BukkitLanguageMessages.EDITOR_LOADING, MessageArgument.untrusted("locale", view.locale()));
        complete(player, view, editor.load(view.locale()), null);
    }

    private void complete(ServerPlayer player, View view, CompletableFuture<PluginLanguageEditor.Document> future, Change change) {
        UUID playerId = player.getUUID();
        CompletableFuture<PluginLanguageEditor.Document> previous = pending.put(playerId, future);
        if (previous != null) {
            previous.cancel(true);
        }
        future.whenComplete((document, failure) -> {
            if (closed) {
                return;
            }
            if (!runtime.schedule(() -> completed(player, view, future, change, document, failure), 1L)
                && pending.remove(playerId, future)) {
                views.remove(playerId, view);
            }
        });
    }

    private void completed(ServerPlayer player, View view, CompletableFuture<PluginLanguageEditor.Document> future, Change change,
                           PluginLanguageEditor.Document document, Throwable failure) {
        if (!pending.remove(player.getUUID(), future) || player.hasDisconnected() || !allowed(player)) {
            return;
        }
        if (failure != null) {
            failed(player, failure);
            show(player, view.document() == null ? new View(null, null, null, "", 1, null) : view);
            return;
        }
        if (change != null) {
            saved(player, view, change);
        }
        show(player, new View(document.locale(), document, view.group(), view.filter(), view.page(), view.key()));
    }

    private void beginPrompt(ServerPlayer player, Prompt prompt) {
        UUID playerId = player.getUUID();
        prompts.put(playerId, prompt);
        player.closeContainer();
        if (prompt.key() == null) {
            promptMenu(player, prompt.view(), List.of(localized(player, BukkitLanguageMessages.EDITOR_SEARCH_PROMPT)));
        } else {
            String key = prompt.key().id()
                + (prompt.form() == null ? "" : " [" + partLabel(player, prompt.expected(), prompt.form()) + "]");
            String current = clip(rawValue(prompt.expected(), prompt.form()).replace("\n", "\\n"));
            promptMenu(player, prompt.view(), List.of(
                localized(player, BukkitLanguageMessages.EDITOR_VALUE_PROMPT, MessageArgument.untrusted("key", key)),
                localized(player, BukkitLanguageMessages.EDITOR_CURRENT_VALUE, MessageArgument.trusted("value", current)),
                localized(player, BukkitLanguageMessages.EDITOR_VARIABLES,
                    MessageArgument.untrusted("variables", variables(player, prompt.key()))),
                localized(player, BukkitLanguageMessages.EDITOR_INPUT_GUIDANCE)
            ));
        }
        if (!runtime.schedule(() -> expired(player, prompt), PROMPT_TICKS)) {
            cancel(playerId);
        }
    }

    private void expired(ServerPlayer player, Prompt prompt) {
        if (player.hasDisconnected()) {
            return;
        }
        if (prompts.remove(player.getUUID(), prompt) && allowed(player)) {
            message(player, BukkitLanguageMessages.EDITOR_INPUT_EXPIRED);
            show(player, prompt.view());
        }
    }

    private void submitted(ServerPlayer player, Prompt prompt, String input) {
        if (player.hasDisconnected()) {
            views.remove(player.getUUID(), prompt.view());
            return;
        }
        if (views.get(player.getUUID()) != prompt.view() || !allowed(player)) {
            return;
        }
        if (input.equalsIgnoreCase("cancel")) {
            show(player, prompt.view());
            return;
        }
        if (input.length() > MAXIMUM_INPUT_LENGTH) {
            message(player, BukkitLanguageMessages.EDITOR_INPUT_TOO_LONG, MessageArgument.trusted("maximum", MAXIMUM_INPUT_LENGTH));
            show(player, prompt.view());
            return;
        }
        if (prompt.key() == null) {
            View view = prompt.view();
            show(player, new View(view.locale(), view.document(), view.group(), input.strip(), 1, null));
            return;
        }
        try {
            MessageValue replacement = replacement(prompt.expected(), prompt.form(), decodeInput(input));
            Change change = new Change(prompt.key().id(), rawValue(prompt.expected(), prompt.form()), rawValue(replacement, prompt.form()));
            complete(player, prompt.view(), editor.save(new PluginLanguageEditor.Edit(prompt.view().locale(),
                prompt.key().id(), prompt.expected(), replacement)), change);
        } catch (IllegalArgumentException exception) {
            message(player, BukkitLanguageMessages.EDITOR_UNABLE_TO_SAVE, MessageArgument.untrusted("reason",
                Objects.requireNonNullElse(exception.getMessage(), exception.getClass().getSimpleName())));
            show(player, prompt.view());
        }
    }

    private void show(ServerPlayer player, View requested) {
        if (!allowed(player)) {
            return;
        }
        List<String> locales = requested.locale() == null ? localization.languages().availableLocales() : List.of();
        boolean categoryView = requested.document() != null && requested.group() == null && requested.filter().isEmpty();
        List<String> groups = categoryView ? groups(requested.document()) : List.of();
        List<MessageKey> keys = requested.document() != null && !categoryView && requested.key() == null ? keys(requested) : List.of();
        List<String> parts = requested.key() == null ? List.of() : parts(requested);
        int count = requested.locale() == null ? locales.size()
            : categoryView ? groups.size()
            : requested.key() == null ? keys.size() : parts.size();
        MinecraftDirectorMiniMenu.ContentPage page = MinecraftDirectorMiniMenu.paginate(count, requested.page(), pageSize(requested));
        View view = new View(requested.locale(), requested.document(), requested.group(), requested.filter(), page.page(), requested.key());
        String title = localized(player, BukkitLanguageMessages.EDITOR_TITLE,
            MessageArgument.untrusted("plugin", MinecraftLanguageSwitcher.PLUGIN),
            MessageArgument.untrusted("section", section(player, view))).plain();
        ItemStack[] contents = new ItemStack[SIZE];
        ItemStack filler = formattedItem(Items.STAINED_GLASS_PANE.black(), ComponentText.literal(" "), List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            contents[slot] = filler.copy();
        }
        List<Integer> contentSlots = contentSlots(view, page.endIndex() - page.startIndex());
        for (int index = page.startIndex(); index < page.endIndex(); index++) {
            contents[contentSlots.get(index - page.startIndex())] = entry(player, view, locales, groups, keys, parts, index);
        }
        contents[BACK] = formattedItem(Items.ARROW, localized(player, BukkitLanguageMessages.EDITOR_BACK), List.of());
        contents[CLOSE] = formattedItem(Items.BARRIER, localized(player, BukkitLanguageMessages.EDITOR_CLOSE), List.of());
        if (page.hasPrevious()) {
            contents[PREVIOUS] = formattedItem(Items.ARROW, localized(player, BukkitLanguageMessages.EDITOR_PREVIOUS_PAGE), List.of());
        }
        if (page.hasNext()) {
            contents[NEXT] = formattedItem(Items.ARROW, localized(player, BukkitLanguageMessages.EDITOR_NEXT_PAGE), List.of());
        }
        if (view.document() != null && view.group() == null && view.key() == null && view.filter().isEmpty()) {
            contents[SEARCH] = formattedItem(Items.COMPASS,
                themed(player, BukkitLanguageMessages.EDITOR_SEARCH_MESSAGES, THEME.primaryRight()),
                List.of(themed(player, BukkitLanguageMessages.EDITOR_SEARCH_MESSAGES_LORE, THEME.description())));
        }
        if (view.document() != null && view.group() == null && view.key() == null && !view.filter().isEmpty()) {
            contents[SEARCH] = formattedItem(Items.PAPER,
                themed(player, BukkitLanguageMessages.EDITOR_CLEAR_SEARCH, THEME.primaryRight()), List.of());
        }
        views.put(player.getUUID(), view);
        player.openMenu(new SimpleMenuProvider((id, inventory, ignored) ->
            new EditorMenu(id, inventory, this, player, view, contents), Component.literal(title)));
        if (player.containerMenu instanceof EditorMenu opened && opened.owner == this && opened.view == view) {
            openMenus.put(player.getUUID(), opened);
            if (closed) {
                player.closeContainer();
                openMenus.remove(player.getUUID(), opened);
            }
        }
    }

    private String section(ServerPlayer player, View view) {
        if (view.locale() == null) {
            return localized(player, BukkitLanguageMessages.EDITOR_LANGUAGES).plain();
        }
        if (view.group() != null) {
            return localized(player, BukkitLanguageMessages.EDITOR_SECTION_GROUP,
                MessageArgument.untrusted("locale", view.locale()),
                MessageArgument.untrusted("group", groupName(view.group()))).plain();
        }
        if (!view.filter().isEmpty()) {
            return localized(player, BukkitLanguageMessages.EDITOR_SECTION_SEARCH,
                MessageArgument.untrusted("locale", view.locale())).plain();
        }
        return view.locale();
    }

    private ItemStack entry(ServerPlayer player, View view, List<String> locales, List<String> groups, List<MessageKey> keys,
                            List<String> parts, int index) {
        if (view.locale() == null) {
            String locale = locales.get(index);
            boolean active = locale.equalsIgnoreCase(localization.languages().defaultLocale());
            return formattedItem(active ? Items.WRITABLE_BOOK : Items.BOOK,
                localeTitle(locale, VolmitLocales.displayName(locale).orElse(locale), active),
                List.of(localized(player, BukkitLanguageMessages.EDITOR_OPEN_LANGUAGE)));
        }
        if (view.group() == null && view.filter().isEmpty()) {
            String group = groups.get(index);
            return formattedItem(groupMaterial(group), categoryTitle(groupName(group)), List.of(
                localized(player, BukkitLanguageMessages.EDITOR_MESSAGE_COUNT,
                    MessageArgument.trusted("count", messageCount(view.document(), group))),
                localized(player, BukkitLanguageMessages.EDITOR_OPEN_CATEGORY)));
        }
        if (view.key() == null) {
            MessageKey key = keys.get(index);
            return messageItem(player, key, view.document().snapshot().value(key), null);
        }
        return messageItem(player, view.key(), view.document().snapshot().value(view.key()), parts.get(index));
    }

    private ItemStack messageItem(ServerPlayer player, MessageKey key, MessageValue value, String form) {
        List<ComponentText> lore = new ArrayList<>();
        lore.add(localized(player, BukkitLanguageMessages.EDITOR_CURRENT_VALUE_LABEL));
        for (String line : preview(rawValue(value, form), 44, 6,
            localized(player, BukkitLanguageMessages.EDITOR_EMPTY).legacy())) {
            lore.add(ComponentText.section(line));
        }
        if (!key.placeholders().isEmpty()) {
            lore.add(ComponentText.empty());
            lore.add(localized(player, BukkitLanguageMessages.EDITOR_VARIABLE_LIST,
                MessageArgument.untrusted("variables", variables(player, key))));
        }
        TextKey instruction = form == null && value instanceof PluralValue
            ? BukkitLanguageMessages.EDITOR_EDIT_PLURAL
            : form == null && value instanceof LinesValue
            ? BukkitLanguageMessages.EDITOR_EDIT_LINES
            : BukkitLanguageMessages.EDITOR_EDIT_CHAT;
        lore.add(localized(player, instruction));
        return formattedItem(Items.PAPER, ComponentText.markup("&f" + MinecraftDirectorMiniMenu.escapeText(
            form == null ? key.id() : partLabel(player, value, form)) + "&r"), lore);
    }

    private static ItemStack formattedItem(Item material, ComponentText name, List<ComponentText> lore) {
        ItemStack stack = new ItemStack(material);
        stack.set(DataComponents.CUSTOM_NAME, MinecraftLegacyText.component(name.legacy()));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>(lore.size());
            for (ComponentText line : lore) {
                lines.add(MinecraftLegacyText.component(line.legacy()));
            }
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
        stack.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.ATTRIBUTE_MODIFIERS, true));
        return stack;
    }

    private static int pageSize(View view) {
        return view.locale() != null && view.group() == null && view.filter().isEmpty() ? CATEGORY_PAGE_SIZE : PAGE_SIZE;
    }

    private static List<Integer> contentSlots(View view, int count) {
        if (view.locale() != null && view.group() == null && view.filter().isEmpty()) {
            return categorySlots(count);
        }
        ArrayList<Integer> slots = new ArrayList<>(count);
        for (int slot = 0; slot < count; slot++) {
            slots.add(slot);
        }
        return List.copyOf(slots);
    }

    private static int contentOrdinal(View view, int slot) {
        if (view.locale() != null && view.group() == null && view.filter().isEmpty()) {
            MinecraftDirectorMiniMenu.ContentPage page = MinecraftDirectorMiniMenu.paginate(
                groups(view.document()).size(), view.page(), pageSize(view));
            return categorySlots(page.endIndex() - page.startIndex()).indexOf(slot);
        }
        return slot < pageSize(view) ? slot : -1;
    }

    private boolean allowed(ServerPlayer player) {
        if (closed) {
            return false;
        }
        if (runtime.access().permission(player, "volmit.language.admin")
            || runtime.access().permission(player, MinecraftLanguageSwitcher.ADMIN_PERMISSION)) {
            return true;
        }
        cancel(player.getUUID());
        message(player, BukkitLanguageMessages.EDITOR_NO_PERMISSION, MessageArgument.untrusted("plugin", MinecraftLanguageSwitcher.PLUGIN));
        player.closeContainer();
        return false;
    }

    private void cancel(UUID playerId) {
        prompts.remove(playerId);
        views.remove(playerId);
        CompletableFuture<PluginLanguageEditor.Document> future = pending.remove(playerId);
        if (future != null) {
            future.cancel(true);
        }
    }

    private void failed(ServerPlayer player, Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        LOGGER.warn("Unable to edit {} language messages", MinecraftLanguageSwitcher.PLUGIN, cause);
        message(player, BukkitLanguageMessages.EDITOR_UNABLE_TO_EDIT, MessageArgument.untrusted("reason",
            Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName())));
    }

    private void message(ServerPlayer player, TextKey key, MessageArgument... arguments) {
        player.sendSystemMessage(MinecraftDirectorMiniMenu.component(themed(player, key, THEME.description(), arguments)));
    }

    private ComponentText themed(ServerPlayer player, TextKey key, String color, MessageArgument... arguments) {
        return localized(player, key, arguments).colorIfAbsent(color);
    }

    private ComponentText localized(ServerPlayer player, TextKey key, MessageArgument... arguments) {
        MessageArgs.Builder builder = MessageArgs.builder();
        for (MessageArgument argument : arguments) {
            builder.add(argument);
        }
        return localized(localization.languages(), player, key, builder.build());
    }

    private String partLabel(ServerPlayer player, MessageValue value, String part) {
        if (!(value instanceof LinesValue)) {
            return part;
        }
        return localized(player, BukkitLanguageMessages.EDITOR_LINE, MessageArgument.trusted("line", Integer.parseInt(part) + 1)).plain();
    }

    private String variables(ServerPlayer player, MessageKey key) {
        String names = variableNames(key);
        return names.isEmpty() ? localized(player, BukkitLanguageMessages.EDITOR_NONE).plain() : names;
    }

    private void promptMenu(ServerPlayer player, View view, List<ComponentText> content) {
        ArrayList<String> entries = new ArrayList<>(content.size());
        for (ComponentText line : content) {
            entries.add("<" + THEME.muted() + ">⇀ </" + THEME.muted() + ">" + line.miniMessage());
        }
        String command = "/" + MinecraftLanguageSwitcher.COMMAND + " language server edit " + view.locale();
        MinecraftDirectorMiniMenu.deliverContent(player.createCommandSourceStack(), new MinecraftDirectorMiniMenu.ContentMenu(
            command, "/" + MinecraftLanguageSwitcher.COMMAND + " language server edit", command, entries, "", 1,
            Math.max(1, entries.size())), THEME, key -> localization.directorText(null, key, MessageArgs.empty()));
    }

    private void saved(ServerPlayer player, View view, Change change) {
        promptMenu(player, view, List.of(
            localized(player, BukkitLanguageMessages.EDITOR_SAVED, MessageArgument.untrusted("locale", view.locale())),
            localized(player, BukkitLanguageMessages.EDITOR_CHANGED,
                MessageArgument.untrusted("key", change.key()),
                MessageArgument.trusted("before", clip(change.before())),
                MessageArgument.trusted("after", clip(change.after())))
        ));
    }

    private static List<String> parts(View view) {
        MessageValue value = view.document().snapshot().value(view.key());
        if (value instanceof LinesValue lines) {
            List<String> indices = new ArrayList<>(lines.lines().size());
            for (int index = 0; index < lines.lines().size(); index++) {
                indices.add(Integer.toString(index));
            }
            return indices;
        }
        PluralValue plural = (PluralValue) value;
        return PLURAL_FORMS.stream().filter(plural.forms()::containsKey).toList();
    }

    private static List<MessageKey> keys(View view) {
        return matchingKeys(view.document(), view.group(), view.filter());
    }

    private static int messageCount(PluginLanguageEditor.Document document, String group) {
        int count = 0;
        for (MessageKey key : document.snapshot().catalog().keys()) {
            if (group(key.id()).equals(group)) {
                count++;
            }
        }
        return count;
    }

    private record View(String locale, PluginLanguageEditor.Document document, String group, String filter, int page, MessageKey key) {
    }

    private record Prompt(View view, MessageKey key, String form, MessageValue expected) {
    }

    private record Change(String key, String before, String after) {
    }

    static final class EditorMenu extends ChestMenu {
        private final MinecraftLanguageEditor owner;
        private final ServerPlayer viewer;
        private final View view;

        private EditorMenu(int id, Inventory inventory, MinecraftLanguageEditor owner, ServerPlayer viewer, View view, ItemStack[] contents) {
            super(MenuType.GENERIC_9x6, id, inventory, new SimpleContainer(contents), 6);
            this.owner = owner;
            this.viewer = viewer;
            this.view = view;
        }

        @Override
        public void clicked(int slot, int button, ContainerInput input, Player player) {
            if (player == viewer) {
                owner.routeClick(this, slot, input);
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return player == viewer && !viewer.hasDisconnected();
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            if (player == viewer) {
                owner.menuClosed(this);
            }
        }
    }
}
