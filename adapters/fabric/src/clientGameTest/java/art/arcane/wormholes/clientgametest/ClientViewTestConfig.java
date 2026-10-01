package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.WormholesConfigFile;
import art.arcane.wormholes.util.project.config.TomlCodec;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ClientViewTestConfig {
    private ClientViewTestConfig() {
    }

    static void enable() {
        enable(true);
    }

    static void enable(boolean lightingFidelity) {
        Path directory = FabricLoader.getInstance().getGameDir().resolve("config/wormholes");
        WormholesConfigFile file = new WormholesConfigFile();
        file.clientView.enabled = true;
        file.render.lightingFidelity = lightingFidelity;
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new UncheckedIOException("ClientView test config directory could not be created at " + directory, failure);
        }
        TomlCodec.writeCanonical(directory.resolve(WormholesSettings.CONFIG_FILE_NAME).toFile(), file);
    }
}
