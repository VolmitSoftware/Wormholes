package art.arcane.wormholes.qa;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;

/**
 * Records, since the last {@link #mark()}, every screen the client opened (sampled every millisecond off-thread, so a
 * loading screen that stays up for a single frame is still seen) and every log event at WARN or above that carries an
 * exception or is logged at ERROR.
 */
final class QaWatch {
    private static final int MAX_ERRORS = 50;

    private final Set<String> screens = new LinkedHashSet<>();
    private final Set<String> loadingScreens = new LinkedHashSet<>();
    private final List<String> errors = new ArrayList<>();
    private volatile boolean running;
    private AbstractAppender appender;

    void start(BooleanSupplier finished) {
        running = true;
        installAppender();
        Thread thread = new Thread(() -> {
            while (running && !finished.getAsBoolean()) {
                Minecraft minecraft = Minecraft.getInstance();
                Screen screen = minecraft == null ? null : minecraft.screen;
                if (screen != null) {
                    record(screen);
                }
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException error) {
                    return;
                }
            }
        }, "Wormholes QA screen watch");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (appender != null) {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            context.getConfiguration().getRootLogger().removeAppender(appender.getName());
            context.updateLoggers();
            appender.stop();
            appender = null;
        }
    }

    synchronized void mark() {
        screens.clear();
        loadingScreens.clear();
        errors.clear();
    }

    synchronized List<String> screens() {
        return List.copyOf(screens);
    }

    synchronized List<String> loadingScreens() {
        return List.copyOf(loadingScreens);
    }

    synchronized List<String> errors() {
        return List.copyOf(errors);
    }

    private synchronized void record(Screen screen) {
        String name = screen.getClass().getSimpleName();
        if (screens.add(name)) {
            QaHarness.LOGGER.info("QA screen opened: {}", name);
        }
        if (screen instanceof ReceivingLevelScreen || screen instanceof LevelLoadingScreen || screen instanceof ProgressScreen
            || screen instanceof GenericDirtMessageScreen || screen instanceof ConnectScreen) {
            loadingScreens.add(name);
        }
    }

    private synchronized void recordError(LogEvent event) {
        if (errors.size() >= MAX_ERRORS) {
            return;
        }
        StringBuilder text = new StringBuilder(event.getLevel().name()).append(' ').append(event.getLoggerName()).append(": ")
            .append(event.getMessage().getFormattedMessage());
        if (event.getThrown() != null) {
            text.append(" | ").append(event.getThrown());
        }
        errors.add(text.toString());
    }

    // The four-argument constructor is the one present in every log4j-core that Fabric and Forge 1.20.1 ship.
    @SuppressWarnings("deprecation")
    private void installAppender() {
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            appender = new AbstractAppender("WormholesQaWatch", null, null, true) {
                @Override
                public void append(LogEvent event) {
                    if (event.getLevel().isMoreSpecificThan(Level.ERROR) || event.getThrown() != null) {
                        recordError(event.toImmutable());
                    }
                }
            };
            appender.start();
            context.getConfiguration().getRootLogger().addAppender(appender, Level.WARN, null);
            context.updateLoggers();
        } catch (RuntimeException | LinkageError error) {
            QaHarness.LOGGER.error("Wormholes QA could not install the log watch", error);
            appender = null;
        }
    }
}
