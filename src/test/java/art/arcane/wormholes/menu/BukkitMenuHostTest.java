package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.messaging.Messenger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.menu.language.LanguageMenuController;

class BukkitMenuHostTest {
    private static final UUID VIEWER = UUID.fromString("b0c0f000-0000-4000-8000-0000000000aa");

    private final List<LogRecord> records = new ArrayList<>();

    @TempDir
    private Path data;

    private PluginLanguageEditor editor;
    private Player player;
    private BukkitMenuHost host;

    @BeforeEach
    void setUp() {
        Wormholes plugin = mock(Wormholes.class);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        Server server = mock(Server.class);
        editor = mock(PluginLanguageEditor.class);
        player = mock(Player.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLanguageService()).thenReturn(mock(PluginLanguageService.class));
        when(plugin.getLanguageEditor()).thenReturn(editor);
        when(player.getUniqueId()).thenReturn(VIEWER);
        when(player.hasPermission(LanguageMenuController.ADMIN_PERMISSION)).thenReturn(true);
        when(server.getPlayer(VIEWER)).thenReturn(player);
        when(server.getMessenger()).thenReturn(mock(Messenger.class));
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        host = new BukkitMenuHost(plugin, mock(PortalManager.class), () -> true);
    }

    @Test
    void aFailedLanguageLoadIsLoggedEvenWhenTheViewerCannotBeReached() {
        IOException failure = new IOException("unreadable messages");
        when(editor.load("fr_FR")).thenReturn(CompletableFuture.failedFuture(failure));
        host.start().capabilities().accept(VIEWER, 1);

        assertTrue(host.openLanguageEditor(player, "fr_FR"));

        assertEquals(1, records.size());
        assertEquals(Level.SEVERE, records.getFirst().getLevel());
        assertSame(failure, records.getFirst().getThrown());
    }
}
