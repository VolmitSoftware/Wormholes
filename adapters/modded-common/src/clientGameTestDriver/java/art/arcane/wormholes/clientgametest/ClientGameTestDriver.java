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
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
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
                if (pass.label().equals("dh-acceptance-singleplayer")) {
                    LOGGER.info("[{}] starting", pass.label());
                    pass.body().accept(pass.label());
                    passed.add(pass.label());
                    LOGGER.info("[{}] passed", pass.label());
                    continue;
                }
                if (address.isEmpty()) {
                    ClientViewTestConfig.enableSeamless(true);
                    worlds.singleplayer(() -> pass(client, passed, pass));
                } else {
                    worlds.dedicated(address, () -> pass(client, passed, pass));
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
            if (Boolean.getBoolean("wormholes.test.dhAcceptance")) {
                return List.of(new Pass("dh-acceptance-singleplayer", label -> NativeDistantHorizonsAcceptance.run(client, label)));
            }
            return List.of(
                new Pass("same-dimension-singleplayer", label -> SeamlessSameDimension.seamless(client, server, label)),
                new Pass("cross-dimension-singleplayer-frame", label -> SeamlessCrossDimension.seamless(client, server, label, OrientationPolicy.FRAME)),
                new Pass("cross-dimension-stress-singleplayer", label -> SeamlessCrossDimension.stress(client, server, label)),
                new Pass("walk-nether-portal-singleplayer", label -> SeamlessWalkThrough.netherPortal(client, server, label, WALK_TRIPS)),
                new Pass("walk-frame-portal-singleplayer", label -> SeamlessWalkThrough.framePortal(client, server, label, WALK_TRIPS)),
                new Pass("fall-loop-singleplayer", label -> SeamlessFallLoop.run(client, server, label)));
        }
        return List.of(
            new Pass("same-dimension-dedicated", label -> SeamlessSameDimension.seamless(client, server, label)),
            new Pass("cross-dimension-dedicated-mirror", label -> SeamlessCrossDimension.seamless(client, server, label, OrientationPolicy.MIRROR)),
            new Pass("cross-dimension-stress-dedicated", label -> SeamlessCrossDimension.stress(client, server, label)));
    }

    private static void pass(DriverClient client, List<String> passed, Pass pass) {
        LOGGER.info("[{}] starting", pass.label());
        client.runOnClient(minecraft -> CardinalLightingAssertions.verify());
        LOGGER.info("WORMHOLES_CARDINAL_LIGHTING_SCOPE_PASS");
        client.runOnClient(minecraft -> ClientMeshLightingAssertions.verify(minecraft.level));
        LOGGER.info("WORMHOLES_CLIENT_LIGHTING_SCOPE_PASS");
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
        try {
            if (Level.class.getDeclaredMethod("getBrightness", LightLayer.class, BlockPos.class).getReturnType() != int.class
                || Level.class.getDeclaredMethod("getRawBrightness", BlockPos.class, int.class).getReturnType() != int.class) {
                throw new AssertionError("Streamed scene lighting methods have invalid return types");
            }
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("Streamed scene lighting methods are missing", missing);
        }
        LOGGER.info("WORMHOLES_CLIENT_LIGHTING_MIXIN_PASS");
        LOGGER.info("WORMHOLES_CLIENT_MIXIN_AUDIT complete");
    }

    private record Pass(String label, Consumer<String> body) {
    }
}
