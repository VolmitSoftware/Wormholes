package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.WormholesConfigFile;
import art.arcane.wormholes.util.project.config.TomlCodec;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class ClientViewTestConfig {
    private ClientViewTestConfig() {
    }

    static Properties serverProperties() {
        Properties properties = new Properties();
        properties.setProperty("server-ip", "127.0.0.1");
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            properties.setProperty("server-port", Integer.toString(socket.getLocalPort()));
        } catch (IOException failure) {
            throw new UncheckedIOException("Client GameTest loopback port could not be allocated", failure);
        }
        return properties;
    }

    static void enable() {
        enable(true);
    }

    static void enable(boolean lightingFidelity) {
        write(lightingFidelity);
    }

    static void enableSeamless(boolean seamlessTravel) {
        Path file = write(true);
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            lines.removeIf(line -> line.startsWith("seamless-travel"));
            int section = lines.indexOf("[client-view]");
            if (section < 0) {
                throw new IllegalStateException("ClientView test config has no [client-view] section at " + file);
            }
            lines.add(section + 1, "seamless-travel = " + seamlessTravel);
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("ClientView test config could not select seamless travel at " + file, failure);
        }
    }

    private static Path write(boolean lightingFidelity) {
        Path directory = FabricLoader.getInstance().getGameDir().resolve("config/wormholes");
        WormholesConfigFile file = new WormholesConfigFile();
        file.clientView.enabled = true;
        file.render.lightingFidelity = lightingFidelity;
        file.render.blockEntityContainers = true;
        file.render.blockEntityTypes.add("minecraft:chest");
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new UncheckedIOException("ClientView test config directory could not be created at " + directory, failure);
        }
        Path target = directory.resolve(WormholesSettings.CONFIG_FILE_NAME);
        TomlCodec.writeCanonical(target.toFile(), file);
        return target;
    }
}
