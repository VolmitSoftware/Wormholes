package art.arcane.wormholes.modded;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftStorePathsTest {
    private static final Path GAME = Path.of("/games/minecraft");
    private static final Path SAVE = GAME.resolve("saves/World A");
    private static final Path GAME_STORE = GAME.resolve("config/wormholes");
    private static final Path SAVE_STORE = SAVE.resolve("wormholes");

    @Test
    public void dedicatedServerKeepsEveryStoreUnderItsConfigFolder() {
        MinecraftStorePaths paths = MinecraftStorePaths.dedicated(GAME);

        assertEquals(new MinecraftStorePaths(GAME_STORE, GAME_STORE, GAME_STORE), paths);
    }

    @Test
    public void singleplayerKeepsWorldDataInsideTheSaveByDefault() {
        MinecraftStorePaths paths = MinecraftStorePaths.singleplayer(GAME, SAVE, false);

        assertEquals(GAME_STORE, paths.config());
        assertEquals(SAVE_STORE, paths.data());
        assertEquals(SAVE_STORE, paths.doors());
    }

    @Test
    public void sharedSingleplayerStoreKeepsPortalDataInTheGameFolderAndDoorsInTheSave() {
        MinecraftStorePaths paths = MinecraftStorePaths.singleplayer(GAME, SAVE, true);

        assertEquals(GAME_STORE, paths.config());
        assertEquals(GAME_STORE, paths.data());
        assertEquals(SAVE_STORE, paths.doors());
    }

    @Test
    public void integratedServerResolvesItsOwnSaveFolder() {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isDedicatedServer()).thenReturn(false);
        when(server.getServerDirectory()).thenReturn(GAME);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(SAVE.resolve("."));

        assertEquals(new MinecraftStorePaths(GAME_STORE, SAVE_STORE, SAVE_STORE), MinecraftStorePaths.of(server, false));
        assertEquals(new MinecraftStorePaths(GAME_STORE, GAME_STORE, SAVE_STORE), MinecraftStorePaths.of(server, true));
    }

    @Test
    public void dedicatedServerIgnoresTheSharedSingleplayerOption() {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isDedicatedServer()).thenReturn(true);
        when(server.getServerDirectory()).thenReturn(GAME);

        assertEquals(MinecraftStorePaths.dedicated(GAME), MinecraftStorePaths.of(server, true));
        assertEquals(MinecraftStorePaths.dedicated(GAME), MinecraftStorePaths.of(server, false));
    }

    @Test
    public void configFolderIsTheGameFolderStoreOnEveryServer() {
        assertEquals(GAME_STORE, MinecraftStorePaths.configDirectory(GAME));
    }
}
