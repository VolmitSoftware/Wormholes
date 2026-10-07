package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.WormholesConfigFile;
import art.arcane.wormholes.util.project.config.TomlCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
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
        write(true, true);
    }

    static void enable(boolean lightingFidelity) {
        write(lightingFidelity, true);
    }

    static void enableSeamless(boolean seamlessTravel) {
        write(true, seamlessTravel);
    }

    private static void write(boolean lightingFidelity, boolean seamlessTravel) {
        Path directory = Path.of("config", "wormholes");
        WormholesConfigFile file = new WormholesConfigFile();
        file.clientView.enabled = true;
        file.clientView.seamlessTravel = seamlessTravel;
        file.render.lightingFidelity = lightingFidelity;
        file.render.blockEntityContainers = true;
        file.render.blockEntityTypes.add("minecraft:chest");
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new UncheckedIOException("ClientView test config directory could not be created at " + directory, failure);
        }
        TomlCodec.writeCanonical(directory.resolve(WormholesSettings.CONFIG_FILE_NAME).toFile(), file);
    }
}
