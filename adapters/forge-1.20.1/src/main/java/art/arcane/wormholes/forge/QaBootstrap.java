package art.arcane.wormholes.forge;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** Starts the dev-only QA harness when -Dwormholes.qa is set. Release jars do not contain the harness. */
final class QaBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();

    private QaBootstrap() {
    }

    static void start() {
        String script = System.getProperty("wormholes.qa");
        if (script == null || script.isBlank()) {
            return;
        }
        try {
            Class.forName("art.arcane.wormholes.qa.QaHarness", true, QaBootstrap.class.getClassLoader())
                .getMethod("start", String.class).invoke(null, script);
        } catch (ClassNotFoundException ignored) {
            // Release build: the harness source set is not packaged.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            LOGGER.error("Could not start the Wormholes QA harness", error);
        }
    }
}
