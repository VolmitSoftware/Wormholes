package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesValue;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluralValue;
import art.arcane.volmlib.util.localization.VolmitLocales;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.wormholes.localization.WormholesMessages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.Suggestion;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.HiddenByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class MinecraftLanguageGameTest {
    private static final int CLICK_TICKS = 2;
    private static final String EDITED = "Edited by the language editor test";
    private static final String SEARCH = "portal.edit.denied";

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final MinecraftGameTestPlayer admin;
    private final MinecraftGameTestPlayer member;
    private final AutoCloseable permissions;
    private String original;
    private String titleSeen;
    private int mark;
    private boolean cleaned;

    private MinecraftLanguageGameTest(GameTestHelper helper) {
        this.helper = helper;
        runtime = WormholesGameTests.RUNTIME;
        admin = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "language-admin");
        member = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "language-member");
        permissions = runtime.access().register((player, node) -> player == admin.player() && node.equals("wormholes.admin")
            ? MinecraftAccessService.Decision.ALLOW : MinecraftAccessService.Decision.UNSET);
    }

    public static void run(GameTestHelper helper) {
        MinecraftLanguageGameTest test = new MinecraftLanguageGameTest(helper);
        try {
            test.start();
        } catch (RuntimeException error) {
            test.cleanup();
            throw error;
        }
    }

    private void start() {
        runtime.schedule(this::cleanup, 1190);
        GameTestSequence sequence = helper.startSequence();
        chatMenus(sequence);
        editor(sequence);
        sequence.thenExecute(() -> {
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS language_runtime home_menu permissions completions locale_pages selection_feedback editor_layout click_types loading categories search prompts cancel save restore clear_search back_navigation open_cancel close denied_editor");
            cleanup();
        }).thenSucceed();
    }

    private void chatMenus(GameTestSequence sequence) {
        sequence.thenExecute(() -> {
            List<String> home = command(admin, "wormholes language");
            helper.assertTrue(home.getFirst().equals("\n".repeat(MinecraftDirectorMiniMenu.MENU_LINE_COUNT)), "Language home did not clear the chat");
            helper.assertTrue(home.get(1).contains("/wormholes language"), "Language home banner is missing");
            helper.assertTrue(home.get(2).equals("〈 Back"), "Language home back link differs: " + home.get(2));
            helper.assertTrue(home.subList(3, home.size() - 1).equals(List.of(
                "⇀ Your language - Current: en_US",
                "⇀ Server default - Current: en_US",
                "⇀ Reset your language - Use the server default language.",
                "⇀ Edit language messages - Open the per-language message editor.")), "Admin language home entries differ: " + home);
            Component edit = latest(admin, "⇀ Edit language messages");
            helper.assertTrue(runs(edit, "/wormholes language server edit"), "Editor entry does not run the editor command");
            helper.assertTrue(hovers(edit, "Command: /wormholes language server edit"), "Editor entry hover does not show its command");
            helper.assertTrue(runs(latest(admin, "〈 Back"), "/wormholes"), "Language home back link does not run /wormholes");
            List<String> memberHome = command(member, "wormholes language");
            helper.assertTrue(memberHome.subList(3, memberHome.size() - 1).equals(List.of(
                "⇀ Your language - Current: en_US",
                "⇀ Reset your language - Use the server default language.")), "Member language home entries differ: " + memberHome);
            helper.assertTrue(command(member, "wormholes language server").equals(List.of(
                "You do not have permission to change the server language for Wormholes.")), "Member reached the server language list");
            helper.assertTrue(command(member, "wormholes language server edit").equals(List.of(
                "You do not have permission to change the server language for Wormholes.")), "Member reached the language editor");
            helper.assertTrue(!(member.player().containerMenu instanceof MinecraftLanguageEditor.EditorMenu), "Member opened the language editor");
            helper.assertTrue(suggestions(admin, "wormholes language ").equals(List.of("self", "server")), "Admin language completions differ");
            helper.assertTrue(suggestions(admin, "wormholes language server e").equals(List.of("edit", "en_US", "es_ES")), "Server completions differ");
            helper.assertTrue(suggestions(member, "wormholes language ").equals(List.of("self")), "Member language completions differ");
            helper.assertTrue(suggestions(member, "wormholes language self r").equals(List.of("reset", "ru_RU")), "Self completions differ");
            List<String> self = command(admin, "wormholes language self");
            List<String> locales = runtime.localization().languages().availableLocales();
            helper.assertTrue(self.get(1).contains("/wormholes language self") && self.get(2).equals("〈 Back"), "Self locale list header differs");
            helper.assertTrue(self.get(3).equals("⇀ Your language - Change only the messages you see.")
                && self.get(4).equals("⇀ Server default - Change the server default language"), "Self locale list scope links differ");
            helper.assertTrue(self.get(5).equals("⇀ • " + locales.getFirst() + " - " + VolmitLocales.displayName(locales.getFirst()).orElse(locales.getFirst())),
                "First locale line differs: " + self.get(5));
            helper.assertTrue(self.size() == 15 && self.getLast().endsWith("Page 2 ❭"), "Self locale list is not paged by nine: " + self);
            helper.assertTrue(runs(latest(admin, "Page 2 ❭"), "/wormholes language self page=2"), "Next page does not run the page command");
            helper.assertTrue(runs(latest(admin, "⇀ ✔ en_US - "), "/wormholes language self en_US"), "Selected locale line does not select en_US");
            helper.assertTrue(command(admin, "wormholes language self page=x").equals(List.of("Use a numeric language page.")), "Numeric page error differs");
            helper.assertTrue(command(admin, "wormholes language bogus").equals(List.of(
                "Usage: /wormholes language self [locale|reset] or server [locale]")), "Selection usage differs");
            helper.assertTrue(command(admin, "wormholes language server reset").equals(List.of(
                "Select a locale such as en_US for the server default.")), "Server reset error differs");
            helper.assertTrue(command(admin, "wormholes language self en_US").equals(List.of("Preparing language en_US for Wormholes...")),
                "Personal selection did not prepare");
        }).thenWaitUntil(() -> helper.assertTrue(received(admin, "Wormholes: your language is now en_US."), "Personal selection did not finish"))
            .thenExecute(() -> {
                helper.assertTrue(runtime.localization().languages().playerLocale(admin.player().getUUID()).equals(Optional.of("en_US")),
                    "Personal selection was not stored");
                command(admin, "wormholes language self reset");
            })
            .thenWaitUntil(() -> helper.assertTrue(received(admin, "Wormholes: your language now uses the server default."),
                "Personal reset did not finish"))
            .thenExecute(() -> helper.assertTrue(runtime.localization().languages().playerLocale(admin.player().getUUID()).isEmpty(),
                "Personal reset kept the stored locale"));
    }

    private void editor(GameTestSequence sequence) {
        sequence.thenExecute(() -> {
            command(admin, "wormholes language server edit");
        }).thenWaitUntil(() -> helper.assertTrue(editor() != null, "Language editor did not open")).thenIdle(1).thenExecute(() -> {
            assertEditor("Wormholes › Languages");
            List<String> locales = runtime.localization().languages().availableLocales();
            for (int index = 0; index < locales.size(); index++) {
                String locale = locales.get(index);
                boolean active = locale.equals(runtime.localization().languages().defaultLocale());
                assertItem(index, active ? Items.WRITABLE_BOOK : Items.BOOK, (active ? "✔ " : "• ") + locale + " — "
                    + VolmitLocales.displayName(locale).orElse(locale), List.of("Click to open this language."));
            }
            for (int slot = locales.size(); slot < MinecraftLanguageEditor.SIZE; slot++) {
                if (slot != MinecraftLanguageEditor.BACK && slot != MinecraftLanguageEditor.CLOSE) {
                    assertItem(slot, Items.STAINED_GLASS_PANE.black(), " ", List.of());
                }
            }
            click(locales.indexOf("en_US"), 0, ContainerInput.PICKUP);
        }).thenWaitUntil(() -> helper.assertTrue(categories(), "Language document did not load")).thenIdle(1).thenExecute(() -> {
            helper.assertTrue(received(admin, "Loading en_US messages..."), "Loading notice is missing");
            assertEditor("Wormholes › en_US");
            List<String> groups = MinecraftLanguageEditor.groups(document());
            List<Integer> slots = MinecraftLanguageEditor.categorySlots(groups.size());
            String group = groups.getFirst();
            assertItem(slots.getFirst(), MinecraftLanguageEditor.groupMaterial(group), MinecraftLanguageEditor.groupName(group),
                List.of(count(group) + " messages", "Click to open this category."));
            assertItem(MinecraftLanguageEditor.SEARCH, Items.COMPASS, "Search messages", List.of("Click to search all messages."));
            if (groups.size() > MinecraftLanguageEditor.CATEGORY_PAGE_SIZE) {
                assertItem(MinecraftLanguageEditor.NEXT, Items.ARROW, "Next page", List.of());
            }
            assertItem(MinecraftLanguageEditor.PREVIOUS, Items.STAINED_GLASS_PANE.black(), " ", List.of());
            assertItem(slots.getFirst() - 1, Items.STAINED_GLASS_PANE.black(), " ", List.of());
            click(slots.getFirst(), 1, ContainerInput.PICKUP);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            String group = MinecraftLanguageEditor.groups(document()).getFirst();
            assertEditor("Wormholes › en_US / " + MinecraftLanguageEditor.groupName(group));
            MessageKey first = MinecraftLanguageEditor.matchingKeys(document(), group, "").getFirst();
            ItemStack message = editor().getSlot(0).getItem();
            helper.assertTrue(message.is(Items.PAPER) && name(message).equals(first.id()), "First message tile differs: " + name(message));
            helper.assertTrue(lore(message).getFirst().equals("Current Value:"), "Message tile lore does not start with its value");
            helper.assertTrue(lore(message).getLast().equals(instruction(document().snapshot().value(first))), "Message tile instruction differs");
            click(MinecraftLanguageEditor.BACK, 0, ContainerInput.SWAP);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            assertEditor("Wormholes › en_US");
            mark = admin.messages().size();
            click(MinecraftLanguageEditor.SEARCH, 0, ContainerInput.PICKUP);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            helper.assertTrue(admin.player().containerMenu == admin.player().inventoryMenu, "Search prompt kept the editor open");
            helper.assertTrue(since(admin, mark).contains("⇀ Search message keys or text. Type cancel to return."), "Search prompt is missing");
            helper.assertTrue(runs(latest(admin, "〈 Back"), "/wormholes language server edit en_US"), "Prompt back link does not reopen the locale");
            helper.assertTrue(MinecraftLocalization.chat(admin.player(), SEARCH), "Search prompt did not consume chat");
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            assertEditor("Wormholes › en_US / Search");
            assertItem(MinecraftLanguageEditor.SEARCH, Items.PAPER, "Clear search", List.of());
            helper.assertTrue(name(editor().getSlot(0).getItem()).equals(SEARCH), "Search did not find " + SEARCH);
            original = MinecraftLanguageEditor.rawValue(document().snapshot().value(document().snapshot().catalog().require(SEARCH)), null);
            mark = admin.messages().size();
            click(0, 0, ContainerInput.PICKUP);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            List<String> prompt = since(admin, mark);
            helper.assertTrue(prompt.contains("⇀ Type a new value for " + SEARCH + " in chat.")
                && prompt.contains("⇀ Current Value: " + ComponentText.markup(original).plain())
                && prompt.contains("⇀ Use \\n for new lines, \\ for a backslash, or type cancel to return."), "Value prompt differs: " + prompt);
            helper.assertTrue(MinecraftLocalization.chat(admin.player(), "cancel"), "Value prompt did not consume cancel");
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            assertEditor("Wormholes › en_US / Search");
            click(0, 0, ContainerInput.PICKUP);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            mark = admin.messages().size();
            helper.assertTrue(MinecraftLocalization.chat(admin.player(), EDITED), "Value prompt did not consume the edit");
        }).thenWaitUntil(() -> helper.assertTrue(received(admin, "⇀ Saved en_US. Language selections are unchanged."), "Edit was not saved"))
            .thenWaitUntil(() -> helper.assertTrue(editor() != null && lore(editor().getSlot(0).getItem()).contains(EDITED), "Saved value did not reopen"))
            .thenExecute(() -> {
                helper.assertTrue(since(admin, mark).contains("⇀ " + SEARCH + ": “" + ComponentText.markup(original).plain() + "” changed to “" + EDITED + "”."),
                    "Change summary differs: " + since(admin, mark));
                helper.assertTrue(runtime.localization().text(null, WormholesMessages.PORTAL_EDIT_DENIED, Map.of()).getString().equals(EDITED), "Saved message is not live");
                click(0, 0, ContainerInput.PICKUP);
            }).thenIdle(CLICK_TICKS).thenExecute(() -> helper.assertTrue(MinecraftLocalization.chat(admin.player(),
                original.replace("\\", "\\\\").replace("\n", "\\n")), "Restore prompt did not consume chat"))
            .thenWaitUntil(() -> helper.assertTrue(editor() != null && lore(editor().getSlot(0).getItem()).stream().noneMatch(line -> line.contains(EDITED)),
                "Original value was not restored"))
            .thenExecute(() -> click(MinecraftLanguageEditor.SEARCH, 0, ContainerInput.PICKUP))
            .thenIdle(CLICK_TICKS).thenExecute(() -> {
                assertEditor("Wormholes › en_US");
                click(MinecraftLanguageEditor.BACK, 0, ContainerInput.PICKUP);
            }).thenIdle(CLICK_TICKS).thenExecute(() -> {
                assertEditor("Wormholes › Languages");
                mark = admin.messages().size();
                click(MinecraftLanguageEditor.BACK, 0, ContainerInput.PICKUP);
            }).thenIdle(CLICK_TICKS).thenExecute(() -> {
                helper.assertTrue(admin.player().containerMenu == admin.player().inventoryMenu, "Back from languages kept the editor open");
                List<String> server = since(admin, mark);
                helper.assertTrue(server.size() > 2 && server.get(1).contains("/wormholes language server"), "Back did not show the server locale list");
                command(admin, "wormholes language server edit en_US");
            }).thenWaitUntil(() -> helper.assertTrue(categories(), "Direct locale editor did not open")).thenExecute(() -> {
                click(MinecraftLanguageEditor.SEARCH, 0, ContainerInput.PICKUP);
            }).thenIdle(CLICK_TICKS).thenExecute(() -> {
                admin.player().openMenu(new SimpleMenuProvider((id, inventory, player) -> ChestMenu.threeRows(id, inventory), Component.literal("Other")));
                helper.assertTrue(!MinecraftLocalization.chat(admin.player(), "portal"), "Opening another menu kept the editor prompt");
                admin.player().closeContainer();
                command(admin, "wormholes language server edit");
            }).thenWaitUntil(() -> helper.assertTrue(editor() != null, "Editor did not reopen")).thenExecute(() -> {
                click(MinecraftLanguageEditor.CLOSE, 0, ContainerInput.PICKUP);
            }).thenIdle(CLICK_TICKS).thenExecute(() -> helper.assertTrue(admin.player().containerMenu == admin.player().inventoryMenu,
                "Close did not close the editor"));
    }

    private List<String> command(MinecraftGameTestPlayer connection, String command) {
        int before = connection.messages().size();
        CommandSourceStack source = connection.player().createCommandSourceStack();
        runtime.server().getCommands().performPrefixedCommand(source, command);
        return since(connection, before);
    }

    private List<String> suggestions(MinecraftGameTestPlayer connection, String input) {
        CommandDispatcher<CommandSourceStack> dispatcher = runtime.server().getCommands().getDispatcher();
        try {
            return dispatcher.getCompletionSuggestions(dispatcher.parse(input, connection.player().createCommandSourceStack()))
                .get(5, TimeUnit.SECONDS).getList().stream().map(Suggestion::getText).toList();
        } catch (Exception failure) {
            return List.of("completion failed: " + failure);
        }
    }

    private static List<String> since(MinecraftGameTestPlayer connection, int before) {
        List<Component> messages = connection.messages();
        List<String> lines = new ArrayList<>(messages.size() - before);
        for (Component message : messages.subList(before, messages.size())) {
            lines.add(message.getString());
        }
        return lines;
    }

    private static boolean received(MinecraftGameTestPlayer connection, String text) {
        return connection.messages().stream().anyMatch(message -> message.getString().equals(text));
    }

    private static Component latest(MinecraftGameTestPlayer connection, String text) {
        List<Component> messages = connection.messages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index).getString().contains(text)) {
                return messages.get(index);
            }
        }
        return Component.empty();
    }

    private static boolean runs(Component message, String command) {
        return styles(message).stream().anyMatch(style -> style.getClickEvent() instanceof ClickEvent.RunCommand run
            && run.command().equals(command));
    }

    private static boolean hovers(Component message, String text) {
        return styles(message).stream().anyMatch(style -> style.getHoverEvent() instanceof HoverEvent.ShowText show
            && show.value().getString().contains(text));
    }

    private static List<Style> styles(Component message) {
        List<Style> styles = new ArrayList<>();
        message.visit((style, text) -> {
            styles.add(style);
            return Optional.empty();
        }, Style.EMPTY);
        return styles;
    }

    private MinecraftLanguageEditor.EditorMenu editor() {
        return admin.player().containerMenu instanceof MinecraftLanguageEditor.EditorMenu menu ? menu : null;
    }

    private PluginLanguageEditor.Document document() {
        return new PluginLanguageEditor.Document("en_US", runtime.localization().snapshot(null));
    }

    private boolean categories() {
        return editor() != null && editor().getSlot(MinecraftLanguageEditor.SEARCH).getItem().is(Items.COMPASS);
    }

    private void click(int slot, int button, ContainerInput input) {
        admin.player().containerMenu.clicked(slot, button, input, admin.player());
    }

    private void assertEditor(String title) {
        MinecraftLanguageEditor.EditorMenu menu = editor();
        helper.assertTrue(menu != null, "Language editor is not open for " + title);
        helper.assertTrue(menu.getType() == MenuType.GENERIC_9x6 && menu.getRowCount() == 6, "Language editor is not six rows");
        String opened = openedTitle();
        if (opened != null) {
            titleSeen = opened;
        }
        helper.assertTrue(title.equals(titleSeen), "Language editor title is " + titleSeen + " instead of " + title);
        assertItem(MinecraftLanguageEditor.BACK, Items.ARROW, "Back", List.of());
        assertItem(MinecraftLanguageEditor.CLOSE, Items.BARRIER, "Close", List.of());
        ItemStack filler = menu.getSlot(46).getItem();
        helper.assertTrue(filler.is(Items.STAINED_GLASS_PANE.black()) && name(filler).equals(" "), "Language editor filler differs");
        TooltipDisplay display = filler.get(DataComponents.TOOLTIP_DISPLAY);
        helper.assertTrue(display != null && !display.shows(DataComponents.ATTRIBUTE_MODIFIERS), "Language editor items do not hide attributes");
    }

    private void assertItem(int slot, Item item, String name, List<String> lore) {
        ItemStack stack = editor().getSlot(slot).getItem();
        helper.assertTrue(stack.is(item), "Slot " + slot + " holds " + stack + " instead of " + item);
        helper.assertTrue(name(stack).equals(name), "Slot " + slot + " is named '" + name(stack) + "' instead of '" + name + "'");
        helper.assertTrue(lore(stack).equals(lore), "Slot " + slot + " lore " + lore(stack) + " instead of " + lore);
    }

    private String openedTitle() {
        admin.channel().runPendingTasks();
        String title = null;
        Iterator<Object> packets = admin.channel().outboundMessages().iterator();
        while (packets.hasNext()) {
            Object pending = packets.next();
            Object packet = HiddenByteBuf.unpack(pending);
            if (packet instanceof ByteBuf bytes) {
                packet = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(runtime.server().registryAccess()))
                    .codec().decode(bytes.duplicate());
            }
            if (packet instanceof ClientboundOpenScreenPacket open) {
                title = open.getTitle().getString();
            }
            packets.remove();
            ReferenceCountUtil.release(pending);
        }
        return title;
    }

    private int count(String group) {
        return (int) document().snapshot().catalog().keys().stream().filter(key -> MinecraftLanguageEditor.group(key.id()).equals(group)).count();
    }

    private static String instruction(Object value) {
        return value instanceof PluralValue ? "Click to edit a plural form."
            : value instanceof LinesValue ? "Click to edit individual lines." : "Click to edit in chat.";
    }

    private static String name(ItemStack stack) {
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return name == null ? "" : name.getString();
    }

    private static List<String> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        return lore == null ? List.of() : lore.lines().stream().map(Component::getString).toList();
    }

    private void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        try {
            permissions.close();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not remove language test permissions", failure);
        } finally {
            admin.close();
            member.close();
        }
    }
}
