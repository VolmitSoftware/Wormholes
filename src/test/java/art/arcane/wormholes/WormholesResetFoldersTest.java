package art.arcane.wormholes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WormholesResetFoldersTest {
    @TempDir
    Path dataFolder;

    @Test
    void aFullResetRemovesEveryFeatureFolderAndLeavesUnrelatedFilesAlone() throws IOException {
        for (String folder : WormholesReloadCoordinator.RESET_FOLDERS) {
            write(dataFolder.resolve(folder).resolve("nested/file.json"));
        }
        write(dataFolder.resolve("languages/de_DE.toml"));
        write(dataFolder.resolve("wormholes.toml"));

        WormholesReloadCoordinator.deleteResetFolders(dataFolder);

        for (String folder : WormholesReloadCoordinator.RESET_FOLDERS) {
            assertFalse(Files.exists(dataFolder.resolve(folder)), folder + " survived the reset");
        }
        assertTrue(Files.isRegularFile(dataFolder.resolve("languages/de_DE.toml")));
        assertTrue(Files.isRegularFile(dataFolder.resolve("wormholes.toml")));
    }

    @Test
    void theResetListCarriesEveryHeadlineLaneFolder() {
        assertTrue(WormholesReloadCoordinator.RESET_FOLDERS.containsAll(
            java.util.List.of("portals", "doors", "atlas", "rules", "mesh", "backups", "convoy", "pockets")));
    }

    @Test
    void deletingFoldersThatWereNeverCreatedIsNotAnError() throws IOException {
        WormholesReloadCoordinator.deleteResetFolders(dataFolder.resolve("empty"));
    }

    private static void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{}", StandardCharsets.UTF_8);
    }
}
