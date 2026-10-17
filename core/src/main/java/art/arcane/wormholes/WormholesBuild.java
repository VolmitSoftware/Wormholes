package art.arcane.wormholes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

public final class WormholesBuild {
    private static final String RESOURCE = "/wormholes-build.properties";
    private static final String VERSION = load();

    private WormholesBuild() {
    }

    public static String version() {
        return VERSION;
    }

    private static String load() {
        try (InputStream stream = WormholesBuild.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Wormholes build resource " + RESOURCE + " is missing");
            }
            Properties properties = new Properties();
            properties.load(stream);
            String version = properties.getProperty("version");
            if (version == null || version.isBlank()) {
                throw new IllegalStateException("Wormholes build resource " + RESOURCE + " has no version");
            }
            return version;
        } catch (IOException failure) {
            throw new UncheckedIOException("Wormholes build resource " + RESOURCE + " could not be read", failure);
        }
    }
}
