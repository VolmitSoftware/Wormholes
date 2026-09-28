package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.localization.WormholesMessageRenderer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.Suggestion;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftLanguageSwitcherTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final JsonObject BUKKIT = JsonParser.parseString(MinecraftLanguageSwitcherGolden.BUKKIT).getAsJsonObject();

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private MinecraftLocalization localization;
    private MinecraftAccessService access;
    private ServerPlayer player;
    private Set<String> granted;

    @BeforeClass
    public static void bootstrap() {
        MinecraftPortalToolsTest.bootstrap();
    }

    @Before
    public void start() throws Exception {
        Path directory = temporary.getRoot().toPath();
        Files.createDirectories(directory.resolve("languages"));
        Files.writeString(directory.resolve("languages/fr_FR.toml"), "[portal.edit]\ndenied = 'Refus'\n");
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        access = mock(MinecraftAccessService.class);
        player = mock(ServerPlayer.class);
        granted = Collections.synchronizedSet(new HashSet<>());
        when(runtime.server()).thenReturn(server);
        when(runtime.access()).thenReturn(access);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.setLanguage(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(runtime.schedule(any(Runnable.class), anyLong())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return true;
        });
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(server).execute(any(Runnable.class));
        when(level.getServer()).thenReturn(server);
        when(player.level()).thenReturn(level);
        when(player.getUUID()).thenReturn(PLAYER);
        when(access.permission(any(CommandSourceStack.class), anyString()))
            .thenAnswer(invocation -> granted.contains(invocation.getArgument(1, String.class)));
        localization = new MinecraftLocalization(runtime);
        localization.start(directory, WormholesSettings.loadAll(directory));
    }

    @After
    public void stop() {
        localization.close();
    }

    @Test
    public void playerMenusMatchTheBukkitSwitcher() throws Exception {
        assertScenario("home_admin_personal", true, true, "fr_FR");
        assertScenario("home_player", false, true, null);
        assertScenario("home_denied", false, false, null);
        assertScenario("self_page1_personal", true, true, "fr_FR", "self");
        assertScenario("server_page3", true, true, null, "server", "page=3");
    }

    @Test
    public void errorsAndSelectionFeedbackMatchTheBukkitSwitcher() throws Exception {
        assertScenario("server_denied", false, true, null, "server");
        assertScenario("usage", true, true, null, "bogus");
        assertScenario("unavailable", true, true, null, "self", "xx_XX");
        assertScenario("server_reset", true, true, null, "server", "reset");
        assertScenario("numeric_page", true, true, null, "self", "page=x");
        assertScenario("editor_usage", true, true, null, "server", "edit", "a", "b");
        assertScenario("select_self", true, true, null, "self", "fr_FR");
        assertEquals(Optional.of("fr_FR"), localization.languages().playerLocale(PLAYER));
        assertScenario("select_reset", true, true, "fr_FR", "self", "reset");
        assertEquals(Optional.empty(), localization.languages().playerLocale(PLAYER));
        assertScenario("select_server", true, true, null, "server", "fr_FR");
        assertEquals("fr_FR", localization.languages().defaultLocale());
    }

    @Test
    public void consoleOutputMatchesTheBukkitSwitcher() throws Exception {
        assertConsole("console_home");
        assertConsole("console_server", "server");
        assertConsole("console_self", "self");
        assertConsole("console_edit", "server", "edit");
    }

    @Test
    public void completionsMatchTheBukkitSwitcher() {
        JsonObject expected = BUKKIT.getAsJsonObject("completions");
        MinecraftLanguageSwitcher switcher = switcher();
        for (String key : expected.keySet()) {
            String role = key.substring(0, key.indexOf(':'));
            String[] arguments = key.substring(key.indexOf(':') + 1).split(" ", -1);
            CommandSourceStack source = source(!role.equals("console"));
            permissions(role.equals("admin") || role.equals("console"), !role.equals("console"));
            assertEquals(key, strings(expected.getAsJsonArray(key)), switcher.complete(source, arguments));
        }
    }

    @Test
    public void brigadierSuggestsTheLastLanguageArgument() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        localization.registerCommands(dispatcher);
        permissions(true, true);
        CommandSourceStack source = source(true);
        assertEquals(List.of("self", "server"), suggestions(dispatcher, "wormholes language ", source));
        assertEquals(List.of("edit", "en_US", "es_ES"), suggestions(dispatcher, "wormholes language server e", source));
        assertEquals(List.of("de_DE"), suggestions(dispatcher, "wormholes language server edit de", source));
    }

    @Test
    public void menuLinksKeepTheirClickAndHoverEvents() {
        Component line = MinecraftDirectorMiniMenu.component(ComponentText.markup("<gradient:#d4af37:#8c7a45>Your language</gradient>")
            .clickRunCommand("/wormholes language self").hover(ComponentText.markup("<#8c7a45>Change only the messages you see.")));
        List<Style> styles = new ArrayList<>();
        line.visit((style, text) -> {
            styles.add(style);
            return Optional.empty();
        }, Style.EMPTY);
        assertTrue(styles.stream().allMatch(style -> style.getClickEvent() instanceof ClickEvent.RunCommand command
            && command.command().equals("/wormholes language self")));
        assertTrue(styles.stream().allMatch(style -> style.getHoverEvent() instanceof HoverEvent.ShowText text
            && text.value().getString().equals("Change only the messages you see.")));
    }

    private void assertScenario(String name, boolean admin, boolean self, String personal, String... arguments) throws Exception {
        if (personal == null) {
            localization.selectPlayer(player, "reset").get(5, TimeUnit.SECONDS);
        } else {
            localization.selectPlayer(player, personal).get(5, TimeUnit.SECONDS);
        }
        permissions(admin, self);
        List<Component> messages = new ArrayList<>();
        CommandSourceStack source = source(true, messages);
        switcher().command(source, arguments);
        assertMessages(name, messages);
    }

    private void assertConsole(String name, String... arguments) throws Exception {
        permissions(true, true);
        List<Component> messages = new ArrayList<>();
        switcher().command(source(false, messages), arguments);
        assertMessages(name, messages);
    }

    private void assertMessages(String name, List<Component> messages) throws Exception {
        JsonArray expected = BUKKIT.getAsJsonArray(name);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (messages.size() < expected.size() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        List<Component> received;
        synchronized (messages) {
            received = List.copyOf(messages);
        }
        assertEquals(name + " message count", expected.size(), received.size());
        for (int index = 0; index < expected.size(); index++) {
            String golden = expected.get(index).getAsString();
            Component wanted = golden.startsWith("rich:")
                ? MinecraftDirectorMiniMenu.component(ComponentText.component(WormholesMessageRenderer.markup(golden.substring(5))))
                : Component.literal(golden.substring(6));
            assertEquals(name + " line " + index, wanted, received.get(index));
        }
    }

    private MinecraftLanguageSwitcher switcher() {
        return new MinecraftLanguageSwitcher(mockRuntime(), localization);
    }

    private WormholesModRuntime mockRuntime() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        when(runtime.access()).thenReturn(access);
        when(runtime.schedule(any(Runnable.class), anyLong())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return true;
        });
        return runtime;
    }

    private void permissions(boolean admin, boolean self) {
        granted.clear();
        if (admin) {
            granted.add("wormholes.admin");
        }
        if (self) {
            granted.add("volmit.language.self");
            granted.add("wormholes.language.self");
        }
    }

    private CommandSourceStack source(boolean player) {
        return source(player, new ArrayList<>());
    }

    private CommandSourceStack source(boolean withPlayer, List<Component> messages) {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getPlayer()).thenReturn(withPlayer ? player : null);
        doAnswer(invocation -> {
            synchronized (messages) {
                messages.add(invocation.getArgument(0, Component.class));
            }
            return null;
        }).when(source).sendSystemMessage(any(Component.class));
        return source;
    }

    private static List<String> suggestions(CommandDispatcher<CommandSourceStack> dispatcher, String input, CommandSourceStack source)
        throws Exception {
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, source)).get(5, TimeUnit.SECONDS)
            .getList().stream().map(Suggestion::getText).toList();
    }

    private static List<String> strings(JsonArray values) {
        List<String> strings = new ArrayList<>(values.size());
        for (JsonElement value : values) {
            strings.add(value.getAsString());
        }
        return strings;
    }
}
