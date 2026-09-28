package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.localization.WormholesMessages;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftLocalizationTest {
    @BeforeClass
    public static void bootstrap() {
        MinecraftPortalToolsTest.bootstrap();
        Items.NAME_TAG.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
    }

    @Test
    public void canonicalFilesDriveMenusAndDurablePersonalSelection() throws Exception {
        Path directory = Files.createTempDirectory("wormholes-language-test");
        Files.createDirectories(directory.resolve("languages"));
        Files.writeString(directory.resolve("languages/en_US.toml"), "[portal.edit]\ndenied = 'Denied locally'\n");
        Files.writeString(directory.resolve("languages/fr_FR.toml"), "[portal.edit]\ndenied = 'Accès refusé'\n");
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(runtime.server()).thenReturn(server);
        when(level.getServer()).thenReturn(server);
        when(player.level()).thenReturn(level);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(server).execute(any(Runnable.class));
        WormholesSettings settings = WormholesSettings.loadAll(directory);
        MinecraftLocalization localization = new MinecraftLocalization(runtime);
        localization.start(directory, settings);
        try {
            assertEquals("Denied locally", localization.text(player, WormholesMessages.PORTAL_EDIT_DENIED, Map.of()).getString());
            assertEquals("fr_FR", localization.selectPlayer(player, "fr_FR").get(5, TimeUnit.SECONDS));
            assertEquals("Accès refusé", localization.text(player, WormholesMessages.PORTAL_EDIT_DENIED, Map.of()).getString());
            assertTrue(Files.readString(directory.resolve("languages/language-preferences.properties")).contains("fr_FR"));
            ItemStack rename = MinecraftMenuText.item(player, Items.NAME_TAG, WormholesMessages.PORTAL_MENU_RENAME,
                Map.of("portal", "<red>Literal</red>"));
            assertEquals("Rename Portal", rename.get(DataComponents.CUSTOM_NAME).getString());
            assertTrue(rename.get(DataComponents.LORE).lines().stream().anyMatch(line -> line.getString().contains("<red>Literal</red>")));
            Files.writeString(directory.resolve("languages/fr_FR.toml"), "[portal.edit]\ndenied = ''\n");
            localization.reload(settings).get(5, TimeUnit.SECONDS);
            localization.selectPlayer(player, "fr_FR").get(5, TimeUnit.SECONDS);
            assertEquals("Denied locally", localization.text(player, WormholesMessages.PORTAL_EDIT_DENIED, Map.of()).getString());
            assertEquals("reset", localization.selectPlayer(player, "reset").get(5, TimeUnit.SECONDS));
            assertEquals("Denied locally", localization.text(player, WormholesMessages.PORTAL_EDIT_DENIED, Map.of()).getString());
            WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
            when(runtime.configuration()).thenReturn(configuration);
            when(configuration.setLanguage("fr_FR")).thenReturn(CompletableFuture.failedFuture(new IOException("write failed")));
            Files.writeString(directory.resolve("languages/fr_FR.toml"), "[portal.edit]\ndenied = 'Erreur'\n");
            assertThrows(ExecutionException.class, () -> localization.selectServer("fr_FR").get(5, TimeUnit.SECONDS));
            assertEquals("Denied locally", localization.text(player, WormholesMessages.PORTAL_EDIT_DENIED, Map.of()).getString());
        } finally {
            localization.close();
        }
    }
}
