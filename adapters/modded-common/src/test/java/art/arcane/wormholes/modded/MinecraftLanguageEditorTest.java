package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesValue;
import art.arcane.volmlib.util.localization.PluralValue;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.localization.WormholesMessages;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftLanguageEditorTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @BeforeClass
    public static void bootstrap() {
        MinecraftPortalToolsTest.bootstrap();
    }

    @Test
    public void textLinesAndPluralJsonPreserveTheirMessageShapes() {
        assertEquals(new TextValue("Hello {name}"), MinecraftLanguageEditor.parseValue("\"Hello {name}\""));
        assertEquals(new LinesValue(List.of("First", "Second")), MinecraftLanguageEditor.parseValue("[\"First\",\"Second\"]"));
        assertEquals(new PluralValue(Map.of("one", "One", "other", "Many")),
            MinecraftLanguageEditor.parseValue("{\"one\":\"One\",\"other\":\"Many\"}"));
        assertThrows(IllegalArgumentException.class, () -> MinecraftLanguageEditor.parseValue("[2]"));
    }

    @Test
    public void consoleEditorSavesLiveResetsAndRejectsConcurrentChanges() throws Exception {
        Path directory = temporary.getRoot().toPath();
        Path language = directory.resolve("languages/en_US.toml");
        Files.createDirectories(language.getParent());
        Files.writeString(language, "[portal.edit]\ndenied = 'Original'\n");
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        MinecraftAccessService access = mock(MinecraftAccessService.class);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(runtime.server()).thenReturn(server);
        when(runtime.access()).thenReturn(access);
        when(access.permission(any(CommandSourceStack.class), anyString())).thenReturn(true);
        when(source.getTextName()).thenReturn("Console");
        when(access.permission(source, "wormholes.admin")).thenReturn(false);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(server).execute(any(Runnable.class));
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            messages.add(invocation.<Supplier<Component>>getArgument(0).get().getString());
            return null;
        }).when(source).sendSuccess(any(), anyBoolean());
        doAnswer(invocation -> {
            messages.add(invocation.<Component>getArgument(0).getString());
            return null;
        }).when(source).sendFailure(any());
        MinecraftLocalization localization = new MinecraftLocalization(runtime);
        localization.start(directory, WormholesSettings.loadAll(directory));
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        localization.registerCommands(dispatcher);
        String command = "wormholes language server edit en_US portal.edit.denied";
        try {
            assertEquals(0, dispatcher.execute(command + " set \"Edited\"", source));
            messages.clear();
            dispatcher.execute(command, source);
            await(messages, "Append set");
            dispatcher.execute(command + " set \"Edited\"", source);
            await(messages, "Saved");
            assertEquals("Edited", localization.text(null, WormholesMessages.PORTAL_EDIT_DENIED, Map.of()).getString());
            assertTrue(Files.readString(language).contains("Edited"));
            dispatcher.execute(command + " reset", source);
            await(messages, "Saved");
            assertEquals(MinecraftMenuText.format(WormholesMessages.PORTAL_EDIT_DENIED.english(), Map.of()).getString(), localization.text(null, WormholesMessages.PORTAL_EDIT_DENIED, Map.of()).getString());
            Files.writeString(language, "[portal.edit]\ndenied = 'External'\n");
            dispatcher.execute(command + " set \"Stale\"", source);
            await(messages, "Language edit failed:");
            assertTrue(Files.readString(language).contains("External"));
            ServerPlayer player = mock(ServerPlayer.class);
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            when(source.getPlayer()).thenReturn(player);
            when(source.getEntity()).thenReturn(player);
            dispatcher.execute("wormholes language self reset", source);
            await(messages, "Wormholes: your language now uses the server default.");
        } finally {
            localization.close();
        }
    }

    private static void await(BlockingQueue<String> messages, String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String message = messages.poll(100, TimeUnit.MILLISECONDS);
            if (message != null && message.startsWith(prefix)) {
                return;
            }
        }
        throw new AssertionError("Missing editor feedback: " + prefix);
    }
}
