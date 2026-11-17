package art.arcane.wormholes.qa;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Dev-only scripted client driver. Started reflectively when -Dwormholes.qa=&lt;script&gt; is set, it steps through a
 * line script on the client thread, records waits, screenshots and pixel samples, writes a JSON result and stops the game.
 */
public final class QaHarness {
    static final Logger LOGGER = LogUtils.getLogger();
    private static final long POLL_MILLIS = 100L;
    private static final int SAMPLE_RADIUS = 12;
    private static final int TERRAIN_STABLE_POLLS = 10;
    private static QaHarness active;

    private final Path script;
    private final List<QaStep> steps;
    private final QaReport report;
    private final Map<String, String> variables = new HashMap<>();
    private final AtomicBoolean queued = new AtomicBoolean();
    private volatile boolean finished;
    private Path outDirectory;
    private boolean configured;
    private int index;
    private boolean stepBegun;
    private long stepStarted;
    private static final double WORLD_PROMPT_SECONDS = 20.0D;
    private boolean issued;
    private int streak;
    private List<String> batch;
    private final AtomicReference<String> pendingResult = new AtomicReference<>();
    private CompletableFuture<String> serverTask;

    private final QaWatch watch = new QaWatch();

    private QaHarness(Path script, List<QaStep> steps) {
        this.script = script;
        this.steps = steps;
        this.report = new QaReport(script.toString());
    }

    public static synchronized void start(String scriptPath) {
        if (active != null) {
            return;
        }
        auditMixins();
        Path path = Path.of(scriptPath).toAbsolutePath();
        List<QaStep> steps;
        QaHarness harness;
        try {
            steps = QaStep.parse(path);
            harness = new QaHarness(path, steps);
        } catch (Exception error) {
            harness = new QaHarness(path, List.of());
            harness.report.error("Could not read script: " + error);
        }
        active = harness;
        QaHarness started = harness;
        started.watch.start(() -> started.finished);
        LOGGER.info("Wormholes QA harness running {} ({} steps)", path, harness.steps.size());
        Thread poller = new Thread(harness::pollLoop, "Wormholes QA poller");
        poller.setDaemon(true);
        poller.start();
        Thread watchdog = new Thread(harness::watchdog, "Wormholes QA watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void pollLoop() {
        while (!finished) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && queued.compareAndSet(false, true)) {
                minecraft.execute(() -> {
                    try {
                        poll(minecraft);
                    } catch (Throwable error) {
                        LOGGER.error("QA poll failed", error);
                    } finally {
                        queued.set(false);
                    }
                });
            }
            sleep(POLL_MILLIS);
        }
    }

    private void watchdog() {
        long seconds = Long.getLong("wormholes.qa.timeout", 300L);
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (!finished && System.nanoTime() < deadline) {
            sleep(500L);
        }
        if (finished) {
            return;
        }
        report.error("Watchdog expired after " + seconds + "s at step index " + index);
        report.write(resultFile(), "watchdog");
        LOGGER.error("Wormholes QA watchdog expired; result written to {}", resultFile());
        finished = true;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.execute(() -> shutdown(minecraft));
        }
        forceExitLater();
    }

    private void poll(Minecraft minecraft) {
        if (finished) {
            return;
        }
        if (!configured) {
            configured = true;
            minecraft.options.pauseOnLostFocus = false;
            minecraft.options.onboardAccessibility = false;
            report.graphics(minecraft.options.graphicsMode().get().name());
        }
        while (index < steps.size()) {
            QaStep step = steps.get(index);
            if (!stepBegun) {
                stepBegun = true;
                stepStarted = System.nanoTime();
                issued = false;
                streak = 0;
                batch = null;
                pendingResult.set(null);
                serverTask = null;
            }
            Outcome outcome;
            try {
                outcome = run(minecraft, step);
            } catch (Exception error) {
                outcome = Outcome.fail(error.toString());
            }
            if (outcome == null) {
                return;
            }
            report.step(step, outcome.status(), stepStarted, outcome.detail());
            LOGGER.info("QA line {} [{}] {}{}", step.line(), outcome.status(), step.text(),
                outcome.detail() == null ? "" : " : " + outcome.detail());
            index++;
            stepBegun = false;
            if (outcome.quit()) {
                finish(minecraft, "quit");
                return;
            }
        }
        finish(minecraft, "end of script");
    }

    private Outcome run(Minecraft minecraft, QaStep step) throws IOException {
        String resolved = step.resolve(variables);
        String[] args = resolved.isEmpty() ? new String[0] : resolved.split("\\s+");
        return switch (step.op()) {
            case "world" -> world(minecraft, args);
            case "wait_world" -> inWorld(minecraft) ? Outcome.ok(null) : expired(seconds(args, 0, 120)) ? Outcome.timeout("no level or player") : null;
            case "command" -> command(minecraft, resolved);
            case "commands" -> commands(minecraft, args);
            case "wait_ticks" -> elapsedMillis() >= integer(args, 0, 20) * 50L ? Outcome.ok(null) : null;
            case "wait_terrain" -> terrain(minecraft, seconds(args, 0, 60));
            case "wait_views" -> views(integer(args, 0, 1), seconds(args, 1, 30));
            case "screenshot" -> screenshot(minecraft, args);
            case "sample" -> sample(minecraft, args);
            case "attack" -> attack(minecraft, args);
            case "use" -> use(minecraft, args);
            case "capture_portal" -> capturePortal(minecraft, args);
            case "mirror" -> mirror(minecraft, args);
            case "gui" -> {
                minecraft.options.hideGui = args.length > 0 && args[0].equalsIgnoreCase("off");
                yield Outcome.ok(null);
            }
            case "walk" -> walk(minecraft, args);
            case "look" -> look(minecraft, args);
            case "turn" -> minecraft.player == null ? Outcome.fail("no player") : look(minecraft, new String[] {
                Float.toString(minecraft.player.getYRot() + Float.parseFloat(args.length > 0 ? args[0] : "180")),
                Float.toString(minecraft.player.getXRot())});
            case "expect_dimension" -> expectDimension(minecraft, args);
            case "expect_position" -> expectPosition(minecraft, args);
            case "mark" -> {
                watch.mark();
                yield Outcome.ok(null);
            }
            case "expect_no_loading_screen" -> watch.loadingScreens().isEmpty() ? Outcome.ok(watch.screens().isEmpty() ? null
                : "screens " + watch.screens()) : Outcome.fail("loading screens opened: " + watch.loadingScreens());
            case "expect_no_errors" -> watch.errors().isEmpty() ? Outcome.ok(null) : Outcome.fail(watch.errors().size()
                + " logged errors, first: " + watch.errors().getFirst());
            case "graphics" -> graphics(minecraft, args);
            case "dump_views" -> dumpViews();
            case "quit" -> new Outcome("ok", null, true);
            default -> Outcome.fail("Unknown step " + step.op());
        };
    }

    private Outcome world(Minecraft minecraft, String[] args) {
        if (inWorld(minecraft)) {
            return Outcome.ok(issued ? "opened" : "already open");
        }
        if (args.length == 0) {
            return Outcome.fail("world needs a name");
        }
        String name = args[0];
        Screen screen = minecraft.screen;
        boolean idle = minecraft.getOverlay() == null && minecraft.getSingleplayerServer() == null
            && (screen instanceof TitleScreen || screen instanceof AccessibilityOnboardingScreen || screen instanceof DisconnectedScreen);
        boolean stalled = !issued && minecraft.getOverlay() == null && minecraft.getSingleplayerServer() == null && screen != null
            && !idle && !(screen instanceof LevelLoadingScreen) && !(screen instanceof GenericDirtMessageScreen)
            && !(screen instanceof ReceivingLevelScreen) && expired(WORLD_PROMPT_SECONDS);
        if (stalled) {
            LOGGER.warn("QA world step dismissing {} and recreating {}", screen.getClass().getName(), name);
            minecraft.setScreen(new TitleScreen());
            deleteLevel(minecraft, name);
            idle = true;
        }
        if (!issued && idle) {
            issued = true;
            if (minecraft.getLevelSource().levelExists(name)) {
                minecraft.createWorldOpenFlows().loadLevel(new TitleScreen(), name);
            } else {
                LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                    new GameRules(), WorldDataConfiguration.DEFAULT);
                WorldOptions options = new WorldOptions(WorldOptions.randomSeed(), false, false);
                minecraft.createWorldOpenFlows().createFreshLevel(name, settings, options, registries ->
                    registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
            }
            return null;
        }
        return expired(seconds(args, 1, 180)) ? Outcome.timeout("world did not open; screen "
            + (screen == null ? "none" : screen.getClass().getName())) : null;
    }

    /** Deletes a world that cannot be opened without a prompt; the setup commands rebuild its contents. */
    // Applies every pending mixin now so a broken target fails here with a full list instead of on first class load.
    private static void auditMixins() {
        java.util.Set<String> targets = new java.util.TreeSet<>();
        for (String name : List.of("wormholes.mixins.json", "wormholes.client-a.mixins.json", "wormholes.client-b.mixins.json",
            "wormholes.client-c.mixins.json", "wormholes.client-d.mixins.json", "wormholes.client-e.mixins.json")) {
            try (java.io.InputStream input = QaHarness.class.getClassLoader().getResourceAsStream(name)) {
                if (input == null) {
                    continue;
                }
                com.google.gson.JsonObject config = com.google.gson.JsonParser.parseReader(
                    new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                String pkg = config.get("package").getAsString();
                for (String section : List.of("mixins", "client", "server")) {
                    if (!config.has(section)) {
                        continue;
                    }
                    for (com.google.gson.JsonElement entry : config.getAsJsonArray(section)) {
                        targets.addAll(mixinTargets(pkg + "." + entry.getAsString()));
                    }
                }
            } catch (Exception error) {
                LOGGER.error("Wormholes QA mixin audit could not read {}", name, error);
            }
        }
        int failures = 0;
        for (String target : targets) {
            try {
                Class.forName(target, false, QaHarness.class.getClassLoader());
            } catch (Throwable error) {
                failures++;
                Throwable cause = error;
                while (cause.getCause() != null) {
                    cause = cause.getCause();
                }
                LOGGER.error("Wormholes QA mixin audit: {} -> {}", target, cause.toString());
            }
        }
        LOGGER.info("Wormholes QA mixin audit finished: {} targets, {} failures", targets.size(), failures);
    }

    private static List<String> mixinTargets(String mixinClass) throws java.io.IOException {
        List<String> found = new java.util.ArrayList<>();
        try (java.io.InputStream bytes = QaHarness.class.getClassLoader().getResourceAsStream(mixinClass.replace('.', '/') + ".class")) {
            if (bytes == null) {
                return found;
            }
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_CODE);
            List<org.objectweb.asm.tree.AnnotationNode> annotations = new java.util.ArrayList<>();
            if (node.visibleAnnotations != null) {
                annotations.addAll(node.visibleAnnotations);
            }
            if (node.invisibleAnnotations != null) {
                annotations.addAll(node.invisibleAnnotations);
            }
            for (org.objectweb.asm.tree.AnnotationNode annotation : annotations) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") || annotation.values == null) {
                    continue;
                }
                for (int index = 0; index + 1 < annotation.values.size(); index += 2) {
                    Object value = annotation.values.get(index + 1);
                    if (value instanceof List<?> list) {
                        for (Object item : list) {
                            if (item instanceof org.objectweb.asm.Type type) {
                                found.add(type.getClassName());
                            } else if (item instanceof String text) {
                                found.add(text.replace('/', '.'));
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    private static void deleteLevel(Minecraft minecraft, String name) {
        try (LevelStorageSource.LevelStorageAccess access = minecraft.getLevelSource().createAccess(name)) {
            access.deleteLevel();
        } catch (IOException | RuntimeException error) {
            LOGGER.error("QA could not delete world {}", name, error);
        }
    }

    private Outcome command(Minecraft minecraft, String text) {
        if (minecraft.player == null) {
            return Outcome.fail("no player");
        }
        String command = text.startsWith("/") ? text.substring(1) : text;
        minecraft.player.connection.sendCommand(command);
        return Outcome.ok(null);
    }

    /** commands &lt;file&gt;: sends every non-comment line of a command file (relative to the script), one per poll. */
    private Outcome commands(Minecraft minecraft, String[] args) throws IOException {
        if (batch == null) {
            if (args.length == 0) {
                return Outcome.fail("commands needs a file");
            }
            Path file = script.resolveSibling(args[0]);
            batch = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = line.strip();
                if (!text.isEmpty() && !text.startsWith("#")) {
                    batch.add(text);
                }
            }
            streak = 0;
        }
        if (streak >= batch.size()) {
            return Outcome.ok(batch.size() + " commands");
        }
        Outcome sent = command(minecraft, batch.get(streak));
        if (!sent.status().equals("ok")) {
            return sent;
        }
        streak++;
        return null;
    }

    private Outcome terrain(Minecraft minecraft, double timeout) {
        LevelRenderer renderer = minecraft.levelRenderer;
        boolean ready = inWorld(minecraft) && renderer.hasRenderedAllChunks() && renderer.isChunkCompiled(minecraft.player.blockPosition());
        streak = ready ? streak + 1 : 0;
        if (streak >= TERRAIN_STABLE_POLLS) {
            return Outcome.ok(null);
        }
        return expired(timeout) ? Outcome.timeout("chunk queue not drained") : null;
    }

    private Outcome views(int minimum, double timeout) {
        Integer drawn = drawnViews();
        if (drawn == null) {
            return Outcome.fail("PortalStencilRenderer is not available in this build");
        }
        if (drawn >= minimum) {
            return Outcome.ok("drawnViews=" + drawn);
        }
        return expired(timeout) ? Outcome.timeout("drawnViews=" + drawn + " < " + minimum) : null;
    }

    // Counts views from both renderers: the stencil renderer, and the streamed renderer used under shader packs.
    private Integer drawnViews() {
        art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer stencil =
            art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer.instance();
        art.arcane.wormholes.modded.client.render.ClientPortalRenderer streamed =
            art.arcane.wormholes.modded.client.render.ClientPortalRenderer.instance();
        if (stencil == null && streamed == null) {
            return null;
        }
        int value = (stencil == null ? 0 : stencil.drawnViews()) + (streamed == null ? 0 : streamed.drawnViews());
        report.views(value);
        return value;
    }

    private Outcome screenshot(Minecraft minecraft, String[] args) {
        if (args.length == 0) {
            return Outcome.fail("screenshot needs a name");
        }
        File directory = out().toFile();
        File file = new File(new File(directory, "screenshots"), args[0] + ".png");
        if (!issued) {
            issued = true;
            Screenshot.grab(directory, args[0] + ".png", minecraft.getMainRenderTarget(), message -> pendingResult.set(message.getString()));
            return null;
        }
        String message = pendingResult.get();
        if (message != null) {
            if (file.isFile()) {
                report.screenshot(file.getAbsolutePath());
                return Outcome.ok(file.getAbsolutePath());
            }
            return Outcome.fail(message);
        }
        return expired(15) ? Outcome.timeout("screenshot not written") : null;
    }

    private Outcome sample(Minecraft minecraft, String[] args) {
        if (args.length < 5) {
            return Outcome.fail("sample <name> <fx> <fy> <rrggbb> <tolerance>");
        }
        String name = args[0];
        double fx = Double.parseDouble(args[1]);
        double fy = Double.parseDouble(args[2]);
        int expected = Integer.parseInt(args[3].replace("#", ""), 16);
        int tolerance = Integer.parseInt(args[4]);
        int actual = readPixel(minecraft.getMainRenderTarget(), fx, fy);
        int difference = Math.max(Math.abs(red(actual) - red(expected)),
            Math.max(Math.abs(green(actual) - green(expected)), Math.abs(blue(actual) - blue(expected))));
        boolean pass = difference <= tolerance;
        report.sample(name, fx, fy, expected, actual, tolerance, pass);
        String detail = String.format(Locale.ROOT, "expected %06x actual %06x diff %d", expected, actual, difference);
        return pass ? Outcome.ok(detail) : Outcome.fail(detail);
    }

    /**
     * Reads the median colour, by luminance, of a window of the main target around (fx, fy), measured from the top-left
     * like the screen. The median ignores the scattered portal particles drifting over the sample point.
     */
    private static int readPixel(RenderTarget target, double fx, double fy) {
        int width = target.width;
        int height = target.height;
        int centerX = Math.clamp(Math.round(fx * (width - 1)), 0, width - 1);
        int centerY = Math.clamp(Math.round((1.0 - fy) * (height - 1)), 0, height - 1);
        int x = Math.max(0, centerX - SAMPLE_RADIUS);
        int y = Math.max(0, centerY - SAMPLE_RADIUS);
        int w = Math.min(width, centerX + SAMPLE_RADIUS + 1) - x;
        int h = Math.min(height, centerY + SAMPLE_RADIUS + 1) - y;
        ByteBuffer pixels = MemoryUtil.memAlloc(w * h * 4);
        try {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(x, y, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            int count = w * h;
            int[] colors = new int[count];
            for (int pixel = 0; pixel < count; pixel++) {
                colors[pixel] = (pixels.get(pixel * 4) & 0xFF) << 16 | (pixels.get(pixel * 4 + 1) & 0xFF) << 8 | pixels.get(pixel * 4 + 2) & 0xFF;
            }
            return median(colors);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private static int median(int[] colors) {
        Integer[] sorted = new Integer[colors.length];
        for (int index = 0; index < colors.length; index++) {
            sorted[index] = colors[index];
        }
        Arrays.sort(sorted, Comparator.comparingInt(QaHarness::luminance));
        return sorted[sorted.length / 2];
    }

    private static int luminance(int color) {
        return 299 * red(color) + 587 * green(color) + 114 * blue(color);
    }

    /** walk &lt;ticks&gt; [forward|back|left|right]: holds a movement key for that many client ticks (50 ms each). */
    private Outcome walk(Minecraft minecraft, String[] args) {
        if (minecraft.player == null) {
            return Outcome.fail("no player");
        }
        KeyMapping key = switch (args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "forward") {
            case "back" -> minecraft.options.keyDown;
            case "left" -> minecraft.options.keyLeft;
            case "right" -> minecraft.options.keyRight;
            default -> minecraft.options.keyUp;
        };
        if (elapsedMillis() >= integer(args, 0, 20) * 50L) {
            key.setDown(false);
            return Outcome.ok(position(minecraft));
        }
        key.setDown(true);
        return null;
    }

    /** look &lt;yaw&gt; &lt;pitch&gt;: turns the local player; the next movement packet carries it to the server. */
    private Outcome look(Minecraft minecraft, String[] args) {
        if (minecraft.player == null || args.length < 2) {
            return Outcome.fail("look <yaw> <pitch>");
        }
        float yaw = Float.parseFloat(args[0]);
        float pitch = Float.parseFloat(args[1]);
        minecraft.player.setYRot(yaw);
        minecraft.player.setXRot(pitch);
        minecraft.player.yRotO = yaw;
        minecraft.player.xRotO = pitch;
        minecraft.player.setYHeadRot(yaw);
        minecraft.player.yBodyRot = yaw;
        return Outcome.ok(null);
    }

    private Outcome expectDimension(Minecraft minecraft, String[] args) {
        if (!inWorld(minecraft) || args.length == 0) {
            return Outcome.fail("expect_dimension <id> needs a level");
        }
        String actual = minecraft.level.dimension().location().toString();
        String playerLevel = minecraft.player.level().dimension().location().toString();
        boolean match = actual.equals(args[0]) && playerLevel.equals(args[0]);
        String detail = "level " + actual + " player " + playerLevel + " at " + position(minecraft);
        return match ? Outcome.ok(detail) : Outcome.fail(detail);
    }

    /** expect_position x y z radius: the player's feet are within radius blocks of the point. */
    private Outcome expectPosition(Minecraft minecraft, String[] args) {
        if (!inWorld(minecraft) || args.length < 4) {
            return Outcome.fail("expect_position <x> <y> <z> <radius>");
        }
        Vec3 target = new Vec3(Double.parseDouble(args[0]), Double.parseDouble(args[1]), Double.parseDouble(args[2]));
        double distance = minecraft.player.position().distanceTo(target);
        String detail = String.format(Locale.ROOT, "%s distance %.2f", position(minecraft), distance);
        return distance <= Double.parseDouble(args[3]) ? Outcome.ok(detail) : Outcome.fail(detail);
    }

    /** graphics fast|fancy|fabulous: sets the graphics mode the way the video settings screen does. */
    private Outcome graphics(Minecraft minecraft, String[] args) {
        if (args.length == 0) {
            return Outcome.fail("graphics <fast|fancy|fabulous>");
        }
        GraphicsStatus mode = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "fast" -> GraphicsStatus.FAST;
            case "fabulous" -> GraphicsStatus.FABULOUS;
            default -> GraphicsStatus.FANCY;
        };
        minecraft.options.graphicsMode().set(mode);
        GraphicsStatus actual = minecraft.options.graphicsMode().get();
        report.graphics(actual.name());
        return actual == mode ? Outcome.ok(actual.name()) : Outcome.fail("graphics mode stayed " + actual.name());
    }

    /** dump_views: logs the session's streamed portals and the stencil view tree, for diagnosing missing views. */
    private Outcome dumpViews() {
        art.arcane.wormholes.modded.client.WormholesClient client = art.arcane.wormholes.modded.client.WormholesClient.instance();
        if (client == null) {
            return Outcome.fail("no Wormholes client");
        }
        art.arcane.wormholes.modded.client.ClientViewSession session = client.session();
        StringBuilder text = new StringBuilder("caps=").append(Long.toHexString(session.caps())).append(" state=").append(session.state());
        for (art.arcane.wormholes.modded.client.ClientPortal portal : session.portals().values()) {
            art.arcane.optics.aperture.ApertureDescriptor geometry = portal.geometry();
            art.arcane.optics.stream.EnvironmentState environment = session.environment(portal.portalKey());
            text.append("\n  portal ").append(portal.portalKey()).append(" parent=").append(geometry.parentPortalKey())
                .append(" recursion=").append(geometry.recursionDepth()).append(" mirror=").append(geometry.mirror())
                .append(" origin=").append(geometry.originX()).append(',').append(geometry.originY()).append(',').append(geometry.originZ())
                .append(" source=").append(session.rootSourceWorld(portal.portalKey()))
                .append(" env=").append(environment == null ? null : environment.world().dimensionKey())
                .append(" local=").append(session.localDestinationWorld(portal.portalKey()));
        }
        art.arcane.wormholes.modded.client.render.stencil.PortalViews views = client.portalViews();
        for (art.arcane.wormholes.modded.client.render.stencil.PortalView view : views.current()) {
            dumpView(text, views, view, 1);
        }
        LOGGER.info("QA views: {}", text);
        return Outcome.ok(null);
    }

    private static void dumpView(StringBuilder text, art.arcane.wormholes.modded.client.render.stencil.PortalViews views,
                                 art.arcane.wormholes.modded.client.render.stencil.PortalView view, int depth) {
        text.append("\n").append("  ".repeat(depth)).append("view ").append(view.kind()).append(' ')
            .append(view.source().dimension().location()).append(" -> ").append(view.destination().dimension().location())
            .append(" recursion=").append(view.recursion()).append(" stream=").append(views.streamKey(view));
        if (depth < 4) {
            for (art.arcane.wormholes.modded.client.render.stencil.PortalView child : views.children(view)) {
                dumpView(text, views, child, depth + 1);
            }
        }
    }

    private static String position(Minecraft minecraft) {
        Vec3 position = minecraft.player.position();
        return String.format(Locale.ROOT, "%s %.2f %.2f %.2f yaw %.1f", minecraft.player.level().dimension().location(),
            position.x, position.y, position.z, minecraft.player.getYRot());
    }

    private Outcome attack(Minecraft minecraft, String[] args) {
        if (minecraft.gameMode == null || minecraft.player == null) {
            return Outcome.fail("no player");
        }
        BlockPos position = position(args);
        minecraft.gameMode.startDestroyBlock(position, Direction.UP);
        return Outcome.ok(null);
    }

    private Outcome use(Minecraft minecraft, String[] args) {
        if (minecraft.gameMode == null || minecraft.player == null) {
            return Outcome.fail("no player");
        }
        BlockPos position = position(args);
        minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false));
        return Outcome.ok(null);
    }

    /** capture_portal &lt;var&gt; x y z: stores the id of the portal whose aperture holds that block in the player's dimension. */
    private Outcome capturePortal(Minecraft minecraft, String[] args) {
        if (args.length < 4) {
            return Outcome.fail("capture_portal <var> <x> <y> <z>");
        }
        String name = args[0];
        BlockPos position = new BlockPos(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
        Outcome outcome = onServer(minecraft, (server, player) -> {
            WormholesModRuntime runtime = WormholesModRuntime.forServer(server);
            if (runtime == null) {
                return "!Wormholes runtime is not running";
            }
            MinecraftPortal portal = runtime.portals().at(player.serverLevel(), position);
            return portal == null ? "!No portal at " + position.toShortString() : portal.getId().toString();
        });
        if (outcome != null && outcome.status().equals("ok")) {
            variables.put(name, outcome.detail());
        }
        return outcome;
    }

    /** mirror &lt;portal id&gt; [true|false]: toggles mirror mode on the integrated server, the same mutation the settings menu applies. */
    private Outcome mirror(Minecraft minecraft, String[] args) {
        if (args.length == 0) {
            return Outcome.fail("mirror <portal id> [true|false]");
        }
        UUID id = UUID.fromString(args[0]);
        boolean enabled = args.length < 2 || Boolean.parseBoolean(args[1]);
        return onServer(minecraft, (server, player) -> {
            WormholesModRuntime runtime = WormholesModRuntime.forServer(server);
            if (runtime == null) {
                return "!Wormholes runtime is not running";
            }
            boolean changed = runtime.portals().update(player, id, portal -> portal.setMirrorMode(enabled));
            return changed ? "mirrorMode=" + enabled : "!Portal update refused";
        });
    }

    private Outcome onServer(Minecraft minecraft, ServerQuery query) {
        if (serverTask == null) {
            MinecraftServer server = minecraft.getSingleplayerServer();
            if (server == null || minecraft.player == null) {
                return Outcome.fail("no integrated server");
            }
            UUID playerId = minecraft.player.getUUID();
            serverTask = server.submit(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                return player == null ? "!Server player missing" : query.run(server, player);
            });
            return null;
        }
        if (!serverTask.isDone()) {
            return expired(10) ? Outcome.timeout("server task did not finish") : null;
        }
        String result;
        try {
            result = serverTask.join();
        } catch (RuntimeException error) {
            return Outcome.fail(error.toString());
        }
        return result.startsWith("!") ? Outcome.fail(result.substring(1)) : Outcome.ok(result);
    }

    private void finish(Minecraft minecraft, String reason) {
        if (finished) {
            return;
        }
        finished = true;
        drawnViews();
        watch.stop();
        report.write(resultFile(), reason);
        LOGGER.info("Wormholes QA finished ({}); result written to {}", reason, resultFile());
        shutdown(minecraft);
        forceExitLater();
    }

    private static void shutdown(Minecraft minecraft) {
        try {
            if (minecraft.level != null) {
                minecraft.level.disconnect();
                minecraft.clearLevel(new GenericDirtMessageScreen(Component.literal("Saving QA world")));
            }
        } catch (RuntimeException error) {
            LOGGER.error("QA world shutdown failed", error);
        }
        minecraft.stop();
    }

    /** A last resort so a hung shutdown can never leave a dev client running. */
    private static void forceExitLater() {
        Thread halt = new Thread(() -> {
            sleep(60_000L);
            LOGGER.error("Wormholes QA forcing exit after a stalled shutdown");
            Runtime.getRuntime().halt(3);
        }, "Wormholes QA exit");
        halt.setDaemon(true);
        halt.start();
    }

    private Path out() {
        if (outDirectory == null) {
            String configured = System.getProperty("wormholes.qa.out");
            Minecraft minecraft = Minecraft.getInstance();
            Path base = minecraft != null ? minecraft.gameDirectory.toPath() : Path.of("");
            outDirectory = configured != null && !configured.isBlank() ? Path.of(configured) : base.resolve("wormholes-qa");
            try {
                Files.createDirectories(outDirectory);
            } catch (Exception error) {
                LOGGER.error("Could not create QA output directory {}", outDirectory, error);
            }
        }
        return outDirectory.toAbsolutePath();
    }

    private Path resultFile() {
        return out().resolve("result.json");
    }

    private static boolean inWorld(Minecraft minecraft) {
        return minecraft.level != null && minecraft.player != null;
    }

    private long elapsedMillis() {
        return (System.nanoTime() - stepStarted) / 1_000_000L;
    }

    private boolean expired(double seconds) {
        return elapsedMillis() >= (long) (seconds * 1000.0);
    }

    private static BlockPos position(String[] args) {
        if (args.length < 3) {
            throw new IllegalArgumentException("expected <x> <y> <z>");
        }
        return new BlockPos(Integer.parseInt(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]));
    }

    private static int integer(String[] args, int index, int fallback) {
        return args.length > index ? Integer.parseInt(args[index]) : fallback;
    }

    private static double seconds(String[] args, int index, double fallback) {
        return args.length > index ? Double.parseDouble(args[index]) : fallback;
    }

    private static int red(int rgb) {
        return rgb >> 16 & 0xFF;
    }

    private static int green(int rgb) {
        return rgb >> 8 & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface ServerQuery {
        String run(MinecraftServer server, ServerPlayer player);
    }

    private record Outcome(String status, String detail, boolean quit) {
        static Outcome ok(String detail) {
            return new Outcome("ok", detail, false);
        }

        static Outcome fail(String detail) {
            return new Outcome("fail", detail, false);
        }

        static Outcome timeout(String detail) {
            return new Outcome("timeout", detail, false);
        }
    }
}
