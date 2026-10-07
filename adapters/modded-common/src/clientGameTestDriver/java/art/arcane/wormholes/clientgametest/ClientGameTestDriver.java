package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.transit.OrientationPolicy;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.NarratorStatus;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ClientGameTestDriver {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String ENABLED = "wormholes.clientgametest";
    private static final String SERVER = "wormholes.clientgametest.server";
    private static final String SERVER_SEAMLESS = "wormholes.clientgametest.serverSeamless";
    private static final String FILTER = "wormholes.clientgametest.filter";
    private static final int RENDER_DISTANCE = 5;
    private static final int FRAMERATE_LIMIT = 120;
    private static final int WALK_TRIPS = 2;
    private static volatile DriverClient driver;

    private ClientGameTestDriver() {
    }

    public static void clientTick(Minecraft minecraft) {
        DriverClient current = driver;
        if (current != null) {
            current.ticked();
            return;
        }
        if (!Boolean.getBoolean(ENABLED) || minecraft.gui.overlay() != null || minecraft.gui.screen() == null || minecraft.level != null) {
            return;
        }
        DriverClient started = new DriverClient(minecraft);
        driver = started;
        Thread thread = new Thread(() -> System.exit(run(started) ? 0 : 1), "Wormholes client gametest");
        thread.setDaemon(true);
        thread.start();
    }

    public static void cursorFrame() {
        DriverClient current = driver;
        if (current != null) {
            current.cursorFrame();
        }
    }

    public static void systemMessage(String text) {
        DriverClient current = driver;
        if (current != null) {
            current.systemMessage(text);
        }
    }

    private static boolean run(DriverClient client) {
        List<String> passed = new ArrayList<>();
        try {
            client.runOnClient(ClientGameTestDriver::prepare);
            client.runOnClient(ignored -> auditMixins());
            String address = System.getProperty(SERVER, "").trim();
            String filter = System.getProperty(FILTER, "").trim();
            DriverWorlds worlds = new DriverWorlds(client);
            for (Pass pass : passes(client, address)) {
                if (!pass.label().contains(filter)) {
                    continue;
                }
                if (address.isEmpty()) {
                    ClientViewTestConfig.enableSeamless(pass.seamless());
                    worlds.singleplayer(() -> pass(passed, pass));
                } else {
                    worlds.dedicated(address, () -> pass(passed, pass));
                }
            }
            LOGGER.info("WORMHOLES_CLIENT_GAME_TEST_PASS {}", passed);
            return true;
        } catch (Throwable failure) {
            LOGGER.error("WORMHOLES_CLIENT_GAME_TEST_FAIL after passing {}", passed, failure);
            return false;
        }
    }

    private static List<Pass> passes(DriverClient client, String address) {
        CommandSeamlessServer server = new CommandSeamlessServer(client);
        if (address.isEmpty()) {
            return List.of(
                new Pass("same-dimension-prepared-singleplayer", false, label -> SeamlessSameDimension.prepared(client, server, label)),
                new Pass("cross-dimension-prepared-singleplayer", false, label -> SeamlessCrossDimension.prepared(client, server, label)),
                new Pass("same-dimension-singleplayer", true, label -> SeamlessSameDimension.seamless(client, server, label)),
                new Pass("cross-dimension-singleplayer-frame", true,
                    label -> SeamlessCrossDimension.seamless(client, server, label, OrientationPolicy.FRAME)),
                new Pass("cross-dimension-stress-singleplayer", true, label -> SeamlessCrossDimension.stress(client, server, label)),
                new Pass("walk-nether-portal-singleplayer", true, label -> SeamlessWalkThrough.netherPortal(client, server, label, WALK_TRIPS)),
                new Pass("walk-frame-portal-singleplayer", true, label -> SeamlessWalkThrough.framePortal(client, server, label, WALK_TRIPS)),
                new Pass("fall-loop-singleplayer", true, label -> SeamlessFallLoop.run(client, server, label)));
        }
        if (Boolean.parseBoolean(System.getProperty(SERVER_SEAMLESS, "true"))) {
            return List.of(
                new Pass("same-dimension-dedicated", true, label -> SeamlessSameDimension.seamless(client, server, label)),
                new Pass("cross-dimension-dedicated-mirror", true,
                    label -> SeamlessCrossDimension.seamless(client, server, label, OrientationPolicy.MIRROR)),
                new Pass("cross-dimension-stress-dedicated", true, label -> SeamlessCrossDimension.stress(client, server, label)));
        }
        return List.of(
            new Pass("same-dimension-prepared-dedicated", false, label -> SeamlessSameDimension.prepared(client, server, label)),
            new Pass("cross-dimension-prepared-dedicated", false, label -> SeamlessCrossDimension.prepared(client, server, label)));
    }

    private static void pass(List<String> passed, Pass pass) {
        LOGGER.info("[{}] starting", pass.label());
        pass.body().accept(pass.label());
        passed.add(pass.label());
        LOGGER.info("[{}] passed", pass.label());
    }

    private static void prepare(Minecraft minecraft) {
        Options options = minecraft.options;
        options.pauseOnLostFocus = false;
        options.onboardAccessibility = false;
        options.skipMultiplayerWarning = true;
        options.joinedFirstServer = true;
        options.tutorialStep = TutorialSteps.NONE;
        options.renderDistance().set(RENDER_DISTANCE);
        options.framerateLimit().set(FRAMERATE_LIMIT);
        options.enableVsync().set(false);
        options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
        options.cloudStatus().set(CloudStatus.OFF);
        options.chunkSectionFadeInTime().set(0.0D);
        options.narrator().set(NarratorStatus.OFF);
        options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(0.0D);
        minecraft.gui.setScreen(new TitleScreen());
    }

    private static void auditMixins() {
        MixinEnvironment.getCurrentEnvironment().audit();
        LOGGER.info("WORMHOLES_CLIENT_MIXIN_AUDIT complete");
    }

    private record Pass(String label, boolean seamless, Consumer<String> body) {
    }
}
