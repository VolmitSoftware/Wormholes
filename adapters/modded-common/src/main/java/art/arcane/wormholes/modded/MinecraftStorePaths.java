package art.arcane.wormholes.modded;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Objects;

public record MinecraftStorePaths(Path config, Path data, Path doors) {
    private static final String SAVE_FOLDER = "wormholes";

    public MinecraftStorePaths {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(doors, "doors");
    }

    public static MinecraftStorePaths of(MinecraftServer server, boolean sharedSingleplayer) {
        if (server.isDedicatedServer()) {
            return dedicated(server.getServerDirectory());
        }
        return singleplayer(server.getServerDirectory(), server.getWorldPath(LevelResource.ROOT), sharedSingleplayer);
    }

    public static MinecraftStorePaths dedicated(Path serverDirectory) {
        Path config = configDirectory(serverDirectory);
        return new MinecraftStorePaths(config, config, config);
    }

    public static MinecraftStorePaths singleplayer(Path gameDirectory, Path saveDirectory, boolean shared) {
        Path config = configDirectory(gameDirectory);
        Path save = saveDirectory.normalize().resolve(SAVE_FOLDER);
        return new MinecraftStorePaths(config, shared ? config : save, save);
    }

    public static Path configDirectory(Path gameDirectory) {
        return gameDirectory.resolve("config").resolve("wormholes");
    }
}
