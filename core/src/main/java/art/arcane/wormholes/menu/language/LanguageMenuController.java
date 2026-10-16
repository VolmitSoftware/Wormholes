package art.arcane.wormholes.menu.language;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;

import art.arcane.volmlib.util.localization.BukkitLanguageMessages;
import art.arcane.volmlib.util.localization.LinesValue;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageValue;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluralValue;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.VolmitLocales;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestClick;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.ChestTarget;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuActionOrigin;
import art.arcane.wormholes.menu.MenuBindings;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuContext;
import art.arcane.wormholes.menu.MenuController;
import art.arcane.wormholes.menu.MenuDecision;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuElementCommon;
import art.arcane.wormholes.menu.MenuFeedbackStyle;
import art.arcane.wormholes.menu.MenuGuard;
import art.arcane.wormholes.menu.MenuGuards;
import art.arcane.wormholes.menu.MenuHandler;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.MenuNotice;
import art.arcane.wormholes.menu.MenuNotices;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuResult;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.NavigateMode;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.TextElement;

public final class LanguageMenuController implements MenuController {
    public static final String PLUGIN = "Wormholes";
    public static final String VOLMIT_ADMIN_PERMISSION = "volmit.language.admin";
    public static final String ADMIN_PERMISSION = "wormholes.admin";
    public static final int PAGE_SIZE = 45;

    private static final String LOCALE_PREFIX = "language-locale-";
    private static final String GROUP_PREFIX = "language-group-";
    private static final String KEY_PREFIX = "language-key-";
    private static final String FORM_PREFIX = "language-form-";
    private static final String BACK = "language-back";
    private static final String PREVIOUS = "language-previous";
    private static final String SEARCH = "language-search";
    private static final String CLEAR_SEARCH = "language-clear-search";
    private static final String NEXT = "language-next";
    private static final String CLOSE = "language-close";
    private static final String CANCEL = "cancel";
    private static final String THEME_COLOR = "#8c7a45";
    private static final String PROMPT_PREFIX = ComponentText.markup("<" + THEME_COLOR + ">⇀ </" + THEME_COLOR + ">").legacy() + "§r";
    private static final int PREVIEW_WIDTH = 44;
    private static final int PREVIEW_LINES = 6;
    private static final int ROW_WIDTH = 9;
    private static final int FOOTER_ROW = 5;
    private static final Set<MenuInteraction> PRESS = Set.of(MenuInteraction.PRIMARY);
    private static final Set<MenuInteraction> TEXT = Set.of(MenuInteraction.SET_TEXT);
    private static final MenuGuard NONE = MenuGuards.none();

    private final LanguageMenuHost languages;

    public LanguageMenuController(LanguageMenuHost languages) {
        this.languages = Objects.requireNonNull(languages, "languages");
    }

    public static MenuNotice loadingNotice(MenuViewer viewer, String locale) {
        return chat(viewer, BukkitLanguageMessages.EDITOR_LOADING, MessageArgs.builder().untrusted("locale", locale).build(), MenuFeedbackStyle.INFO);
    }

    public static MenuNotice unableToEditNotice(MenuViewer viewer, Throwable cause) {
        return chat(viewer, BukkitLanguageMessages.EDITOR_UNABLE_TO_EDIT, reason(cause), MenuFeedbackStyle.ERROR);
    }

    @Override
    public String id() {
        return LanguageRoutes.CONTROLLER;
    }

    @Override
    public MenuDecision routeGuard(MenuContext context) {
        MenuViewer viewer = context.viewer();
        if (!viewer.hasPermission(VOLMIT_ADMIN_PERMISSION) && !viewer.hasPermission(ADMIN_PERMISSION)) {
            return MenuDecision.denyAndClose(chat(viewer, BukkitLanguageMessages.EDITOR_NO_PERMISSION, plugin(), MenuFeedbackStyle.ERROR));
        }
        MenuRoute route = context.route();
        if (route.screen().equals(LanguageRoutes.LOCALES)) {
            return MenuDecision.allow();
        }
        Optional<LanguageMenuView> document = languages.document(LanguageRoutes.locale(route));
        if (document.isEmpty()) {
            return MenuDecision.deny(unavailable(viewer));
        }
        if (route.screen().equals(LanguageRoutes.FORMS)
            && document.get().message(LanguageRoutes.key(route)).filter(message -> !(message.value() instanceof TextValue)).isEmpty()) {
            return MenuDecision.deny(unavailable(viewer));
        }
        return MenuDecision.allow();
    }

    @Override
    public MenuBuild build(MenuContext context) {
        return switch (context.route().screen()) {
            case LanguageRoutes.LOCALES -> locales(context);
            case LanguageRoutes.GROUPS -> groups(context, document(context.route()));
            case LanguageRoutes.KEYS -> keys(context, document(context.route()));
            case LanguageRoutes.FORMS -> forms(context, document(context.route()));
            default -> throw new IllegalArgumentException("language menus have no screen " + context.route().screen());
        };
    }

    private MenuBuild locales(MenuContext context) {
        MenuStrings strings = context.strings();
        List<String> locales = languages.locales();
        String defaultLocale = languages.defaultLocale();
        Paging paging = Paging.of(context.page(), locales.size(), PAGE_SIZE);
        List<MenuElement> elements = new ArrayList<>(paging.size() + 4);
        MenuBindings.Builder bindings = MenuBindings.builder();

        for (int index = paging.first(); index < paging.last(); index++) {
            String locale = locales.get(index);
            boolean active = locale.equalsIgnoreCase(defaultLocale);
            String id = LOCALE_PREFIX + locale;
            elements.add(new ListEntryElement(MenuElementCommon.builder(id)
                .label(localeTitle(locale, VolmitLocales.displayName(locale).orElse(locale), active))
                .chestHints(List.of(strings.text(BukkitLanguageMessages.EDITOR_OPEN_LANGUAGE)))
                .icon(MenuIcon.item(active ? "minecraft:writable_book" : "minecraft:book"))
                .interactions(PRESS)
                .chestSlot(anyClick(chestSlot(index - paging.first()), MenuInteraction.PRIMARY))
                .build(), active));
            bindings.guard(id, NONE).on(id, MenuInteraction.PRIMARY, (ctx, action) -> load(ctx.viewer(), locale));
        }

        back(elements, bindings, strings, (ctx, action) -> exit(ctx.viewer()));
        previous(elements, bindings, strings, paging);
        next(elements, bindings, strings, paging);
        close(elements, bindings, strings);
        return build(LanguageRoutes.LOCALES, strings, strings.text(BukkitLanguageMessages.EDITOR_LANGUAGES), elements, paging, bindings);
    }

    private MenuBuild groups(MenuContext context, LanguageMenuView document) {
        MenuStrings strings = context.strings();
        String locale = document.locale();
        List<String> groups = document.groups();
        Paging paging = Paging.of(context.page(), groups.size(), LanguageMenuText.CATEGORY_PAGE_SIZE);
        List<Integer> slots = LanguageMenuText.categorySlots(paging.size());
        List<MenuElement> elements = new ArrayList<>(paging.size() + 5);
        MenuBindings.Builder bindings = MenuBindings.builder();
        String searchLabel = strings.text(BukkitLanguageMessages.EDITOR_SEARCH_MESSAGES);
        elements.add(new TextElement(MenuElementCommon.builder(SEARCH)
            .label(themed(searchLabel))
            .nativeLabel(MenuText.stripColors(searchLabel))
            .chestHints(List.of(themed(strings.text(BukkitLanguageMessages.EDITOR_SEARCH_MESSAGES_LORE))))
            .icon(MenuIcon.item("minecraft:compass"))
            .interactions(TEXT)
            .chestSlot(anyClick(new MenuSlot(0, FOOTER_ROW), MenuInteraction.SET_TEXT))
            .build(), "", MenuLimits.MAX_TEXT_INPUT_LENGTH, strings.text(MenuMessages.PLACEHOLDER_SEARCH),
            List.of(prompt(strings.text(BukkitLanguageMessages.EDITOR_SEARCH_PROMPT))), ChestReopen.ALWAYS, null));
        bindings.guard(SEARCH, NONE).on(SEARCH, MenuInteraction.SET_TEXT, (ctx, action) -> search(ctx, action, locale));

        for (int index = paging.first(); index < paging.last(); index++) {
            String group = groups.get(index);
            String id = GROUP_PREFIX + group;
            elements.add(new ListEntryElement(MenuElementCommon.builder(id)
                .label(ComponentText.markup("&d" + escape(LanguageMenuText.groupName(group)) + "&r").legacy())
                .description(List.of(strings.text(BukkitLanguageMessages.EDITOR_MESSAGE_COUNT, MessageArgs.builder()
                    .trusted("count", document.count(group))
                    .build())))
                .chestHints(List.of(strings.text(BukkitLanguageMessages.EDITOR_OPEN_CATEGORY)))
                .icon(MenuIcon.item(LanguageMenuText.groupItem(group)))
                .interactions(PRESS)
                .chestSlot(anyClick(chestSlot(slots.get(index - paging.first())), MenuInteraction.PRIMARY))
                .build(), false));
            bindings.guard(id, NONE)
                .on(id, MenuInteraction.PRIMARY, (ctx, action) -> enter(LanguageRoutes.group(locale, group), NavigateMode.PUSH, 0));
        }

        back(elements, bindings, strings, (ctx, action) -> enter(LanguageRoutes.locales(), NavigateMode.POP_TO, 0));
        previous(elements, bindings, strings, paging);
        next(elements, bindings, strings, paging);
        close(elements, bindings, strings);
        return build(LanguageRoutes.GROUPS, strings, locale, elements, paging, bindings);
    }

    private MenuBuild keys(MenuContext context, LanguageMenuView document) {
        MenuStrings strings = context.strings();
        MenuRoute route = context.route();
        String locale = document.locale();
        String filter = LanguageRoutes.filter(route);
        List<LanguageMenuView.Message> messages = document.matching(LanguageRoutes.group(route), filter);
        Paging paging = Paging.of(context.page(), messages.size(), PAGE_SIZE);
        List<MenuElement> elements = new ArrayList<>(paging.size() + 5);
        MenuBindings.Builder bindings = MenuBindings.builder();
        if (!filter.isEmpty()) {
            String clearLabel = strings.text(BukkitLanguageMessages.EDITOR_CLEAR_SEARCH);
            elements.add(new ButtonElement(MenuElementCommon.builder(CLEAR_SEARCH)
                .label(themed(clearLabel))
                .nativeLabel(MenuText.stripColors(clearLabel))
                .icon(MenuIcon.item("minecraft:paper"))
                .interactions(PRESS)
                .chestSlot(anyClick(new MenuSlot(0, FOOTER_ROW), MenuInteraction.PRIMARY))
                .build()));
            bindings.guard(CLEAR_SEARCH, NONE)
                .on(CLEAR_SEARCH, MenuInteraction.PRIMARY, (ctx, action) -> enter(LanguageRoutes.groups(locale), NavigateMode.POP_TO, 0));
        }

        for (int index = paging.first(); index < paging.last(); index++) {
            LanguageMenuView.Message message = messages.get(index);
            String id = KEY_PREFIX + message.key();
            MenuSlot slot = chestSlot(index - paging.first());
            if (message.value() instanceof TextValue) {
                elements.add(valueElement(strings, id, message, null, slot));
                bindings.guard(id, NONE).on(id, MenuInteraction.SET_TEXT, (ctx, action) -> edit(ctx, action, locale, message, null));
                continue;
            }
            elements.add(new ListEntryElement(messageItem(strings, id, message, null, true)
                .interactions(PRESS)
                .chestSlot(anyClick(slot, MenuInteraction.PRIMARY))
                .build(), false));
            bindings.guard(id, NONE).on(id, MenuInteraction.PRIMARY,
                (ctx, action) -> enter(LanguageRoutes.forms(route, message.key()), NavigateMode.PUSH, 0));
        }

        back(elements, bindings, strings, (ctx, action) -> enter(LanguageRoutes.groups(locale), NavigateMode.POP_TO, 0));
        previous(elements, bindings, strings, paging);
        next(elements, bindings, strings, paging);
        close(elements, bindings, strings);
        return build(LanguageRoutes.KEYS, strings, section(strings, route), elements, paging, bindings);
    }

    private MenuBuild forms(MenuContext context, LanguageMenuView document) {
        MenuStrings strings = context.strings();
        MenuRoute route = context.route();
        String locale = document.locale();
        LanguageMenuView.Message message = document.message(LanguageRoutes.key(route))
            .orElseThrow(() -> new IllegalStateException("message " + LanguageRoutes.key(route) + " is gone from " + locale));
        List<String> parts = LanguageMenuView.parts(message.value());
        Paging paging = Paging.of(context.page(), parts.size(), PAGE_SIZE);
        List<MenuElement> elements = new ArrayList<>(paging.size() + 4);
        MenuBindings.Builder bindings = MenuBindings.builder();

        for (int index = paging.first(); index < paging.last(); index++) {
            String part = parts.get(index);
            String id = FORM_PREFIX + part;
            elements.add(valueElement(strings, id, message, part, chestSlot(index - paging.first())));
            bindings.guard(id, NONE).on(id, MenuInteraction.SET_TEXT, (ctx, action) -> edit(ctx, action, locale, message, part));
        }

        MenuRoute keys = LanguageRoutes.keys(route);
        back(elements, bindings, strings, (ctx, action) -> enter(keys, NavigateMode.POP_TO, keyPage(document, keys, message.key())));
        previous(elements, bindings, strings, paging);
        next(elements, bindings, strings, paging);
        close(elements, bindings, strings);
        return build(LanguageRoutes.FORMS, strings, section(strings, route), elements, paging, bindings);
    }

    private MenuResult load(MenuViewer viewer, String locale) {
        return MenuResult.pending(languages.load(locale).handle((document, failure) -> {
            Throwable cause = logged(failure, "Unable to load " + PLUGIN + " language messages for " + locale);
            return MenuResult.deferred(() -> loaded(viewer, document, cause));
        }), loadingNotice(viewer, locale));
    }

    private MenuResult exit(MenuViewer viewer) {
        languages.exit(viewer);
        return MenuResult.close();
    }

    private MenuResult search(MenuContext context, MenuAction action, String locale) {
        String input = ((MenuValue.Text) action.value()).text();
        if (cancelled(context, input)) {
            return MenuResult.stay();
        }
        String filter = input.strip();
        if (filter.isEmpty()) {
            return MenuResult.stay();
        }
        return enter(LanguageRoutes.search(locale, filter), NavigateMode.PUSH, 0);
    }

    private MenuResult edit(MenuContext context, MenuAction action, String locale, LanguageMenuView.Message message, String form) {
        MenuViewer viewer = context.viewer();
        String input = ((MenuValue.Text) action.value()).text();
        if (cancelled(context, input)) {
            return MenuResult.stay();
        }
        MessageValue expected = message.value();
        PluginLanguageEditor.Edit edit;
        Change change;
        try {
            MessageValue replacement = LanguageMenuView.replaced(expected, form, LanguageMenuText.decodeInput(input));
            change = new Change(locale, message.key(), LanguageMenuView.raw(expected, form), LanguageMenuView.raw(replacement, form));
            edit = new PluginLanguageEditor.Edit(locale, message.key(), expected, replacement);
        } catch (IllegalArgumentException invalid) {
            return MenuResult.stay(chat(viewer, BukkitLanguageMessages.EDITOR_UNABLE_TO_SAVE, reason(invalid), MenuFeedbackStyle.ERROR));
        }
        return MenuResult.pending(languages.apply(edit).handle((ignored, failure) -> {
            Throwable cause = logged(failure, "Unable to save " + PLUGIN + " language message " + message.key() + " in " + locale);
            return MenuResult.deferred(() -> edited(viewer, change, cause));
        }));
    }

    private LanguageMenuView document(MenuRoute route) {
        String locale = LanguageRoutes.locale(route);
        return languages.document(locale).orElseThrow(() -> new IllegalStateException("language " + locale + " is not loaded"));
    }

    private static TextElement valueElement(MenuStrings strings, String id, LanguageMenuView.Message message, String form, MenuSlot slot) {
        String raw = LanguageMenuText.encodeInput(LanguageMenuView.raw(message.value(), form));
        boolean editable = MenuText.codePoints(raw) <= MenuLimits.MAX_TEXT_INPUT_LENGTH;
        String key = message.key() + (form == null ? "" : " [" + partLabel(strings, message.value(), form) + "]");
        List<String> prompt = List.of(
            prompt(strings.text(BukkitLanguageMessages.EDITOR_VALUE_PROMPT, MessageArgs.builder().untrusted("key", key).build())),
            prompt(strings.text(BukkitLanguageMessages.EDITOR_CURRENT_VALUE, MessageArgs.builder()
                .trusted("value", displayed(raw))
                .build())),
            prompt(strings.text(BukkitLanguageMessages.EDITOR_VARIABLES, MessageArgs.builder()
                .untrusted("variables", variables(strings, message))
                .build())),
            prompt(strings.text(BukkitLanguageMessages.EDITOR_INPUT_GUIDANCE)));
        return new TextElement(messageItem(strings, id, message, form, editable)
            .interactions(TEXT)
            .enabled(editable)
            .chestSlot(anyClick(slot, MenuInteraction.SET_TEXT))
            .build(), LanguageMenuText.clipCodePoints(raw, MenuLimits.MAX_TEXT_INPUT_LENGTH), MenuLimits.MAX_TEXT_INPUT_LENGTH,
            strings.text(BukkitLanguageMessages.EDITOR_EMPTY), prompt, ChestReopen.ALWAYS, null);
    }

    private static MenuElementCommon.Builder messageItem(MenuStrings strings, String id, LanguageMenuView.Message message, String form,
                                                         boolean editable) {
        MessageValue value = message.value();
        List<String> description = new ArrayList<>(PREVIEW_LINES + 5);
        description.add(strings.text(BukkitLanguageMessages.EDITOR_CURRENT_VALUE_LABEL));
        description.addAll(LanguageMenuText.preview(LanguageMenuView.raw(value, form), PREVIEW_WIDTH, PREVIEW_LINES,
            strings.text(BukkitLanguageMessages.EDITOR_EMPTY)));
        if (!message.placeholders().isEmpty()) {
            description.add("");
            description.add(LanguageMenuText.fit(strings.text(BukkitLanguageMessages.EDITOR_VARIABLE_LIST, MessageArgs.builder()
                .untrusted("variables", variables(strings, message))
                .build())));
        }
        if (!editable) {
            description.add("");
            description.add(LanguageMenuText.fit(strings.text(BukkitLanguageMessages.EDITOR_INPUT_TOO_LONG, MessageArgs.builder()
                .trusted("maximum", MenuLimits.MAX_TEXT_INPUT_LENGTH)
                .build())));
        }
        TextKey instruction = form == null && value instanceof PluralValue
            ? BukkitLanguageMessages.EDITOR_EDIT_PLURAL
            : form == null && value instanceof LinesValue ? BukkitLanguageMessages.EDITOR_EDIT_LINES : BukkitLanguageMessages.EDITOR_EDIT_CHAT;
        return MenuElementCommon.builder(id)
            .label(ComponentText.markup("&f" + escape(form == null ? message.key() : partLabel(strings, value, form)) + "&r").legacy())
            .description(description)
            .chestHints(editable ? List.of(strings.text(instruction)) : List.of())
            .icon(MenuIcon.item("minecraft:paper"));
    }

    private static void back(List<MenuElement> elements, MenuBindings.Builder bindings, MenuStrings strings, MenuHandler handler) {
        elements.add(navigationButton(strings, BACK, BukkitLanguageMessages.EDITOR_BACK, "minecraft:arrow", -4));
        bindings.guard(BACK, NONE).on(BACK, MenuInteraction.PRIMARY, handler);
    }

    private static void previous(List<MenuElement> elements, MenuBindings.Builder bindings, MenuStrings strings, Paging paging) {
        if (paging.page() == 0) {
            return;
        }
        elements.add(navigationButton(strings, PREVIOUS, BukkitLanguageMessages.EDITOR_PREVIOUS_PAGE, "minecraft:arrow", -1));
        bindings.guard(PREVIOUS, NONE).on(PREVIOUS, MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.page(paging.page() - 1));
    }

    private static void next(List<MenuElement> elements, MenuBindings.Builder bindings, MenuStrings strings, Paging paging) {
        if (paging.page() >= paging.pageCount() - 1) {
            return;
        }
        elements.add(navigationButton(strings, NEXT, BukkitLanguageMessages.EDITOR_NEXT_PAGE, "minecraft:arrow", 1));
        bindings.guard(NEXT, NONE).on(NEXT, MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.page(paging.page() + 1));
    }

    private static void close(List<MenuElement> elements, MenuBindings.Builder bindings, MenuStrings strings) {
        elements.add(navigationButton(strings, CLOSE, BukkitLanguageMessages.EDITOR_CLOSE, "minecraft:barrier", 4));
        bindings.guard(CLOSE, NONE).on(CLOSE, MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.close());
    }

    private static ButtonElement navigationButton(MenuStrings strings, String id, TextKey label, String item, int position) {
        return new ButtonElement(MenuElementCommon.builder(id)
            .label(strings.text(label))
            .icon(MenuIcon.item(item))
            .interactions(PRESS)
            .chestSlot(anyClick(new MenuSlot(position, FOOTER_ROW), MenuInteraction.PRIMARY))
            .nativeHidden(true)
            .build());
    }

    private static MenuBuild build(String screenId, MenuStrings strings, String section, List<MenuElement> elements, Paging paging,
                                   MenuBindings.Builder bindings) {
        String plainSection = MenuText.stripColors(section);
        MenuScreen screen = MenuScreen.builder(screenId)
            .title(strings.text(MenuMessages.TITLE_LANGUAGE))
            .context(plainSection)
            .elements(elements)
            .paging(new MenuPaging(paging.page(), paging.pageCount()))
            .build();
        String title = MenuText.stripColors(strings.text(BukkitLanguageMessages.EDITOR_TITLE, MessageArgs.builder()
            .untrusted("plugin", PLUGIN)
            .untrusted("section", plainSection)
            .build()));
        return new MenuBuild(screen, new ChestLayout(title, 6, ChestPane.BLACK, null), bindings.build());
    }

    private static String section(MenuStrings strings, MenuRoute route) {
        String locale = LanguageRoutes.locale(route);
        String group = LanguageRoutes.group(route);
        if (group != null) {
            return strings.text(BukkitLanguageMessages.EDITOR_SECTION_GROUP, MessageArgs.builder()
                .untrusted("locale", locale)
                .untrusted("group", LanguageMenuText.groupName(group))
                .build());
        }
        return strings.text(BukkitLanguageMessages.EDITOR_SECTION_SEARCH, MessageArgs.builder().untrusted("locale", locale).build());
    }

    private static MenuResult enter(MenuRoute route, NavigateMode mode, int page) {
        return MenuResult.navigate(route, mode, page, null);
    }

    private static boolean cancelled(MenuContext context, String input) {
        return context.origin() == MenuActionOrigin.CHEST && input.equalsIgnoreCase(CANCEL);
    }

    private static MenuResult loaded(MenuViewer viewer, LanguageMenuView document, Throwable cause) {
        if (cause != null) {
            return MenuResult.navigate(LanguageRoutes.locales(), NavigateMode.POP_TO, 0, unableToEditNotice(viewer, cause));
        }
        return enter(LanguageRoutes.groups(document.locale()), NavigateMode.PUSH, 0);
    }

    private static MenuResult edited(MenuViewer viewer, Change change, Throwable cause) {
        if (cause != null) {
            return MenuResult.stay(unableToEditNotice(viewer, cause));
        }
        return MenuResult.stay(saved(viewer, change));
    }

    private static MenuNotice saved(MenuViewer viewer, Change change) {
        MenuStrings strings = viewer.strings();
        String summary = strings.text(BukkitLanguageMessages.EDITOR_SAVED, MessageArgs.builder().untrusted("locale", change.locale()).build());
        String changed = strings.text(BukkitLanguageMessages.EDITOR_CHANGED, MessageArgs.builder()
            .untrusted("key", change.key())
            .trusted("before", displayed(change.before()))
            .trusted("after", displayed(change.after()))
            .build());
        return MenuNotices.text(LanguageMenuText.fit(PROMPT_PREFIX + summary + "\n" + PROMPT_PREFIX + changed), MenuFeedbackStyle.SUCCESS,
            NoticeChannel.CHAT);
    }

    private static MenuNotice unavailable(MenuViewer viewer) {
        return MenuNotices.nativeOnly(viewer, MenuMessages.FEEDBACK_UNAVAILABLE, MessageArgs.empty(), MenuFeedbackStyle.WARNING);
    }

    private static MenuNotice chat(MenuViewer viewer, TextKey key, MessageArgs arguments, MenuFeedbackStyle style) {
        return MenuNotices.text(LanguageMenuText.fit(themed(viewer.strings().text(key, arguments))), style, NoticeChannel.CHAT);
    }

    private static MessageArgs reason(Throwable cause) {
        return MessageArgs.builder()
            .untrusted("reason", Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName()))
            .build();
    }

    private Throwable logged(Throwable failure, String message) {
        if (failure == null) {
            return null;
        }
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        languages.failed(message, cause);
        return cause;
    }

    private static MessageArgs plugin() {
        return MessageArgs.builder().untrusted("plugin", PLUGIN).build();
    }

    private static String partLabel(MenuStrings strings, MessageValue value, String part) {
        if (!(value instanceof LinesValue)) {
            return part;
        }
        return MenuText.stripColors(strings.text(BukkitLanguageMessages.EDITOR_LINE, MessageArgs.builder()
            .trusted("line", Integer.parseInt(part) + 1)
            .build()));
    }

    private static String variables(MenuStrings strings, LanguageMenuView.Message message) {
        if (message.placeholders().isEmpty()) {
            return MenuText.stripColors(strings.text(BukkitLanguageMessages.EDITOR_NONE));
        }
        List<String> names = new ArrayList<>(message.placeholders().size());
        for (String placeholder : message.placeholders()) {
            names.add("{" + placeholder + "}");
        }
        return String.join(" ", names);
    }

    private static String localeTitle(String locale, String name, boolean active) {
        return ComponentText.markup((active ? "<green>✔</green> " : "<dark_gray>•</dark_gray> ")
            + "<white>" + escape(locale) + "</white>"
            + " <dark_gray>—</dark_gray> "
            + "<gray>" + escape(name) + "</gray>").legacy();
    }

    private static String themed(String text) {
        return ComponentText.section(text).colorIfAbsent(THEME_COLOR).legacy();
    }

    private static String displayed(String raw) {
        return ComponentText.normalizeMarkup(LanguageMenuText.clip(raw));
    }

    private static String prompt(String line) {
        return LanguageMenuText.fit(PROMPT_PREFIX + line);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("<", "\\<");
    }

    private static int keyPage(LanguageMenuView document, MenuRoute keys, String key) {
        List<LanguageMenuView.Message> messages = document.matching(LanguageRoutes.group(keys), LanguageRoutes.filter(keys));
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).key().equals(key)) {
                return index / PAGE_SIZE;
            }
        }
        return 0;
    }

    private static MenuSlot chestSlot(int slot) {
        return new MenuSlot(slot % ROW_WIDTH - ROW_WIDTH / 2, slot / ROW_WIDTH);
    }

    private static MenuSlot anyClick(MenuSlot slot, MenuInteraction interaction) {
        MenuSlot mapped = slot;
        for (ChestClick click : ChestClick.values()) {
            mapped = mapped.withClick(click, ChestTarget.self(interaction));
        }
        return mapped;
    }

    private record Change(String locale, String key, String before, String after) {
    }

    private record Paging(int page, int pageCount, int first, int last) {
        static Paging of(int requested, int count, int size) {
            int pageCount = Math.max(1, (count + size - 1) / size);
            int page = Math.max(0, Math.min(requested, pageCount - 1));
            int first = page * size;
            return new Paging(page, pageCount, first, Math.min(count, first + size));
        }

        int size() {
            return last - first;
        }
    }
}
