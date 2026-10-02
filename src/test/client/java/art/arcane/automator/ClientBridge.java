package art.arcane.automator;

import art.arcane.automator.mixin.ContainerScreenAccessor;
import art.arcane.automator.mixin.DefaultPlayerSkinAccessor;
import art.arcane.automator.mixin.KeyMappingAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClientBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("InstanceAutomator");
    private static final long TICK_NANOS = 50_000_000L;
    private static final List<String> INPUT_KEYS = List.of("forward", "back", "left", "right", "jump", "sneak", "sprint", "attack", "use",
            "swapHands", "drop", "inventory");
    private static final Set<String> CLICK_DRIVEN_KEYS = Set.of("swapHands", "drop", "inventory");
    private static final Set<Identifier> DEFAULT_SKIN_TEXTURES = defaultSkinTextures();
    private static final Map<String, Boolean> HELD_KEYS = new HashMap<>();

    private static LoopbackServer server;
    private static Path output;
    private static long ticks;
    private static long frames;
    private static long keyDeadline;
    private static LocalPlayer turningPlayer;
    private static float turnStartYaw;
    private static float turnStartPitch;
    private static float turnTargetYaw;
    private static float turnTargetPitch;
    private static long turnStarted;
    private static long turnDuration;
    private static Screen cursorScreen;
    private static double cursorStartX;
    private static double cursorStartY;
    private static double cursorTargetX;
    private static double cursorTargetY;
    private static long cursorStarted;
    private static long cursorDuration = TICK_NANOS;
    private static PendingWindow pendingWindow;
    private static LiveCapture capture;
    private static long captureFrames;
    private static long captureDroppedFrames;
    private static double captureSeconds;
    private static String captureSource;
    private static String lastError;
    private static String windowError;
    private static String screenshotPath;

    private ClientBridge() {
    }

    public static void tick(Minecraft client) {
        updateLook(client);
        if (server == null) {
            start(client);
        }
        ticks++;
        if ((ticks >= keyDeadline || client.player == null) && !HELD_KEYS.isEmpty()) {
            release(client);
        }
        for (Map.Entry<String, Boolean> key : HELD_KEYS.entrySet()) {
            key(client, key.getKey()).setDown(key.getValue());
        }
        applyWindow(client);
        for (int processed = 0; processed < 16; processed++) {
            LoopbackServer.Request request = server.poll();
            if (request == null) {
                break;
            }
            if (request.result().isCancelled()) {
                continue;
            }
            try {
                request.result().complete(execute(client, request.input()));
            } catch (RuntimeException failure) {
                lastError = failure.toString();
                LOGGER.error("Client automation command failed", failure);
                request.result().completeExceptionally(failure);
            }
        }
    }

    public static void captureFrame(Minecraft client) {
        frames++;
        if (capture != null) {
            capture.frame(client);
        }
    }

    public static void updateLook(Minecraft client) {
        if (turningPlayer == null) {
            return;
        }
        if (client.player != turningPlayer || client.getConnection() == null) {
            turningPlayer = null;
            return;
        }
        double progress = Math.clamp((System.nanoTime() - turnStarted) / (double) turnDuration, 0.0, 1.0);
        double eased = progress * progress * (3.0 - 2.0 * progress);
        float yaw = (float) (turnStartYaw + (turnTargetYaw - turnStartYaw) * eased);
        float pitch = (float) (turnStartPitch + (turnTargetPitch - turnStartPitch) * eased);
        turningPlayer.setYRot(yaw);
        turningPlayer.setXRot(pitch);
        turningPlayer.yRotO = yaw;
        turningPlayer.xRotO = pitch;
        if (progress >= 1.0) {
            turningPlayer = null;
        }
    }

    public static boolean hasActiveAttackLease() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.getConnection() != null && ticks < keyDeadline
                && HELD_KEYS.getOrDefault("attack", false);
    }

    public static boolean hasCursor() {
        Screen screen = Minecraft.getInstance().gui.screen();
        return cursorScreen != null && screen == cursorScreen && screen instanceof AbstractContainerScreen<?>;
    }

    public static double cursorX() {
        return cursorStartX + (cursorTargetX - cursorStartX) * cursorProgress();
    }

    public static double cursorY() {
        return cursorStartY + (cursorTargetY - cursorStartY) * cursorProgress();
    }

    public static void close() {
        if (capture != null) {
            capture.abort();
            capture = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
    }

    private static void start(Minecraft client) {
        client.options.pauseOnLostFocus = false;
        String port = requiredProperty("automator.port");
        String token = requiredProperty("automator.token");
        output = Path.of(requiredProperty("automator.output")).toAbsolutePath().normalize();
        try {
            Files.createDirectories(output);
            server = new LoopbackServer(Integer.parseInt(port), token);
            LOGGER.info("Instance Automator listening on 127.0.0.1:{} with output {}", server.port(), output);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot start client automation bridge", failure);
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + name + " before launching Instance Automator");
        }
        return value;
    }

    private static void openWorld(Minecraft client, String name) {
        if (client.level != null || client.getConnection() != null) {
            throw new IllegalStateException("Disconnect before opening a singleplayer world");
        }
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("World name must identify one save directory");
        }
        if (!client.getLevelSource().levelExists(name)) {
            throw new IllegalArgumentException("World does not exist: " + name);
        }
        client.createWorldOpenFlows().openWorld(name, () -> client.gui.setScreen(new TitleScreen()));
    }

    private static JsonObject execute(Minecraft client, JsonObject input) {
        String operation = text(input, "op");
        if (!operation.equals("state")) {
            client.getFramerateLimitTracker().onInputReceived();
        }
        switch (operation) {
            case "state" -> { }
            case "connect" -> connect(client, text(input, "address"));
            case "open-world" -> openWorld(client, text(input, "name"));
            case "server-command" -> {
                IntegratedServer integrated = client.getSingleplayerServer();
                if (integrated == null) {
                    throw new IllegalStateException("An integrated server must be running");
                }
                String command = text(input, "command");
                integrated.execute(() -> integrated.getCommands().performPrefixedCommand(integrated.createCommandSourceStack(), command));
            }
            case "disconnect" -> {
                turningPlayer = null;
                pendingWindow = null;
                release(client);
                client.disconnectWithSavingScreen();
                client.gui.setScreen(new TitleScreen());
            }
            case "quit" -> {
                release(client);
                client.stop();
            }
            case "fit-window" -> LiveCapture.fitWindow(client, integer(input, "width"), integer(input, "height"));
            case "hud" -> {
                if (client.gui.hud.isHidden() == input.get("visible").getAsBoolean()) {
                    client.gui.hud.toggle();
                }
            }
            case "look" -> look(client, input);
            case "look-at" -> lookAt(client, input);
            case "keys" -> keys(client, input);
            case "release" -> release(client);
            case "click" -> click(client, text(input, "key"));
            case "slot" -> selectSlot(client, integer(input, "index"));
            case "chat" -> chat(client, input);
            case "cursor" -> cursor(client, input);
            case "window" -> window(client, input);
            case "dismiss" -> {
                pendingWindow = null;
                client.gui.setScreen(null);
            }
            case "screenshot" -> screenshot(client.gameRenderer.mainRenderTarget(), text(input, "name"));
            case "portal-screenshot" -> screenshot(WormholesStatus.portalTarget(integer(input, "portalKey")), text(input, "name"));
            case "portal-block" -> {
                JsonObject result = snapshot(client);
                result.add("portalBlock", WormholesStatus.portalBlock(integer(input, "portalKey"), integer(input, "x"),
                    integer(input, "y"), integer(input, "z")));
                return result;
            }
            case "capture" -> capture(client, input);
            case "shader" -> IrisStatus.configure(input);
            case "clear-errors" -> {
                lastError = null;
                windowError = null;
            }
            default -> throw new IllegalArgumentException("Unknown operation " + operation);
        }
        return snapshot(client);
    }

    private static void connect(Minecraft client, String address) {
        if (!address.matches("127\\.0\\.0\\.1:[0-9]{1,5}")) {
            throw new IllegalArgumentException("Only explicit IPv4 loopback servers are accepted");
        }
        ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(address),
                new ServerData("Instance Automator", address, ServerData.Type.OTHER), false, null);
    }

    private static void requirePlayer(Minecraft client) {
        if (client.player == null || client.getConnection() == null) {
            throw new IllegalStateException("Client is not connected to a world");
        }
    }

    private static void look(Minecraft client, JsonObject input) {
        requirePlayer(client);
        float yaw = input.get("yaw").getAsFloat();
        float pitch = input.get("pitch").getAsFloat();
        int duration = optionalInt(input, "ticks", 0);
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || Math.abs(pitch) > 90) {
            throw new IllegalArgumentException("Finite yaw and pitch between -90 and 90 required");
        }
        if (duration < 0 || duration > 200) {
            throw new IllegalArgumentException("Look ticks must be between 0 and 200");
        }
        updateLook(client);
        turningPlayer = null;
        if (duration == 0) {
            client.player.setYRot(yaw);
            client.player.setXRot(pitch);
            return;
        }
        turningPlayer = client.player;
        turnStartYaw = turningPlayer.getYRot();
        turnStartPitch = turningPlayer.getXRot();
        turnTargetYaw = turnStartYaw + Mth.wrapDegrees(yaw - turnStartYaw);
        turnTargetPitch = pitch;
        turnStarted = System.nanoTime();
        turnDuration = duration * TICK_NANOS;
    }

    private static void lookAt(Minecraft client, JsonObject input) {
        requirePlayer(client);
        Vec3 eye = client.player.getEyePosition();
        double dx = input.get("x").getAsDouble() - eye.x;
        double dy = input.get("y").getAsDouble() - eye.y;
        double dz = input.get("z").getAsDouble() - eye.z;
        JsonObject angles = new JsonObject();
        angles.addProperty("yaw", Math.toDegrees(Math.atan2(-dx, dz)));
        angles.addProperty("pitch", -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
        angles.addProperty("ticks", optionalInt(input, "ticks", 0));
        look(client, angles);
    }

    private static void keys(Minecraft client, JsonObject input) {
        requirePlayer(client);
        int lease = optionalInt(input, "leaseTicks", 100);
        if (lease < 1 || lease > 200) {
            release(client);
            throw new IllegalArgumentException("leaseTicks must be between 1 and 200");
        }
        for (String name : INPUT_KEYS) {
            if (input.has(name)) {
                setKey(client, name, input.get(name).getAsBoolean());
            }
        }
        if (input.has("hold")) {
            for (JsonElement name : input.getAsJsonArray("hold")) {
                setKey(client, name.getAsString(), true);
            }
        }
        keyDeadline = ticks + lease;
    }

    private static void click(Minecraft client, String name) {
        requirePlayer(client);
        KeyMapping mapping = key(client, name);
        KeyMapping.click(((KeyMappingAccessor) mapping).currentKey());
    }

    private static KeyMapping key(Minecraft client, String name) {
        return switch (name) {
            case "forward" -> client.options.keyUp;
            case "back" -> client.options.keyDown;
            case "left" -> client.options.keyLeft;
            case "right" -> client.options.keyRight;
            case "jump" -> client.options.keyJump;
            case "sneak" -> client.options.keyShift;
            case "sprint" -> client.options.keySprint;
            case "attack" -> client.options.keyAttack;
            case "use" -> client.options.keyUse;
            case "swapHands" -> client.options.keySwapOffhand;
            case "drop" -> client.options.keyDrop;
            case "inventory" -> client.options.keyInventory;
            default -> throw new IllegalArgumentException("Unknown input key " + name);
        };
    }

    private static void setKey(Minecraft client, String name, boolean down) {
        KeyMapping mapping = key(client, name);
        if (down && CLICK_DRIVEN_KEYS.contains(name) && !HELD_KEYS.getOrDefault(name, false)) {
            KeyMapping.click(((KeyMappingAccessor) mapping).currentKey());
        }
        HELD_KEYS.put(name, down);
        mapping.setDown(down);
    }

    private static void release(Minecraft client) {
        for (String name : HELD_KEYS.keySet()) {
            key(client, name).setDown(false);
        }
        HELD_KEYS.clear();
    }

    private static void selectSlot(Minecraft client, int index) {
        requirePlayer(client);
        if (index < 0 || index >= Inventory.SELECTION_SIZE) {
            throw new IllegalArgumentException("Hotbar index must be between 0 and " + (Inventory.SELECTION_SIZE - 1));
        }
        client.player.getInventory().setSelectedSlot(index);
    }

    private static void chat(Minecraft client, JsonObject input) {
        requirePlayer(client);
        if (input.has("text") == input.has("command")) {
            throw new IllegalArgumentException("Provide exactly one of text or command");
        }
        if (input.has("command")) {
            String command = text(input, "command");
            client.player.connection.sendCommand(command.startsWith("/") ? command.substring(1) : command);
        } else {
            client.player.connection.sendChat(text(input, "text"));
        }
    }

    private static double cursorProgress() {
        double progress = Math.clamp((System.nanoTime() - cursorStarted) / (double) cursorDuration, 0.0, 1.0);
        return progress * progress * (3.0 - 2.0 * progress);
    }

    private static void cursor(Minecraft client, JsonObject input) {
        AbstractContainerScreen<?> screen = requireContainer(client, input);
        double x = input.get("x").getAsDouble();
        double y = input.get("y").getAsDouble();
        int duration = optionalInt(input, "ticks", 1);
        if (duration < 1 || duration > 200) {
            throw new IllegalArgumentException("Cursor ticks must be between 1 and 200");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0 || x >= screen.width || y >= screen.height) {
            throw new IllegalArgumentException("Cursor coordinates must be inside the GUI");
        }
        moveCursor(client, screen, x, y, duration);
    }

    private static void moveCursor(Minecraft client, AbstractContainerScreen<?> screen, double x, double y, int duration) {
        double startX = client.mouseHandler.getScaledXPos(client.getWindow());
        double startY = client.mouseHandler.getScaledYPos(client.getWindow());
        cursorStartX = startX;
        cursorStartY = startY;
        cursorTargetX = x;
        cursorTargetY = y;
        cursorStarted = System.nanoTime();
        cursorDuration = duration * TICK_NANOS;
        cursorScreen = screen;
    }

    private static AbstractContainerScreen<?> requireContainer(Minecraft client, JsonObject input) {
        requirePlayer(client);
        if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            throw new IllegalStateException("No container screen is open");
        }
        if (input.has("containerId") && integer(input, "containerId") != client.player.containerMenu.containerId) {
            throw new IllegalStateException("Container changed before input");
        }
        return screen;
    }

    private static void window(Minecraft client, JsonObject input) {
        AbstractContainerScreen<?> screen = requireContainer(client, input);
        String action = text(input, "action");
        if (action.equals("list")) {
            return;
        }
        if (action.equals("close")) {
            pendingWindow = null;
            screen.onClose();
            return;
        }
        if (pendingWindow != null) {
            throw new IllegalStateException("Wait for the pending window input before sending another");
        }
        if (!Set.of("click", "shift", "drop", "hover").contains(action)) {
            throw new IllegalArgumentException("window action must be list, click, shift, drop, hover or close");
        }
        AbstractContainerMenu menu = client.player.containerMenu;
        int index = integer(input, "slot");
        if (index < 0 || index >= menu.slots.size()) {
            throw new IllegalArgumentException("Slot must be between 0 and " + (menu.slots.size() - 1));
        }
        int button = optionalInt(input, "button", 0);
        if (button != 0 && button != 1) {
            throw new IllegalArgumentException("button must be 0 or 1");
        }
        Slot slot = menu.slots.get(index);
        ContainerScreenAccessor position = (ContainerScreenAccessor) screen;
        double x = position.automatorLeftPos() + slot.x + 8;
        double y = position.automatorTopPos() + slot.y + 8;
        double distance = Math.hypot(x - client.mouseHandler.getScaledXPos(client.getWindow()),
                y - client.mouseHandler.getScaledYPos(client.getWindow()));
        int travelTicks = Math.clamp((int) Math.ceil(distance / 60), 2, 6);
        moveCursor(client, screen, x, y, travelTicks);
        windowError = null;
        pendingWindow = new PendingWindow(screen, menu.containerId, action, index, button,
                cursorStarted + cursorDuration + TICK_NANOS);
    }

    private static void applyWindow(Minecraft client) {
        PendingWindow pending = pendingWindow;
        if (pending == null || System.nanoTime() < pending.due()) {
            return;
        }
        pendingWindow = null;
        try {
            requirePlayer(client);
            if (client.gui.screen() != pending.screen() || client.player.containerMenu.containerId != pending.containerId()) {
                throw new IllegalStateException("Container changed during cursor travel");
            }
            if (pending.action().equals("hover")) {
                return;
            }
            ContainerInput type = switch (pending.action()) {
                case "click" -> ContainerInput.PICKUP;
                case "shift" -> ContainerInput.QUICK_MOVE;
                case "drop" -> ContainerInput.THROW;
                default -> throw new IllegalArgumentException("Unknown pending window action " + pending.action());
            };
            client.gameMode.handleContainerInput(pending.containerId(), pending.slot(), pending.button(), type, client.player);
        } catch (RuntimeException failure) {
            windowError = failure.toString();
            lastError = windowError;
            LOGGER.error("Container input failed after cursor travel", failure);
        }
    }

    private static void screenshot(RenderTarget target, String name) {
        if (!name.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("Screenshot name must contain 1-80 letters, digits, dashes or underscores");
        }
        screenshotPath = null;
        Screenshot.grab(output.toFile(), name + ".png", target, 1, message -> {
            screenshotPath = output.resolve("screenshots").resolve(name + ".png").toString();
            LOGGER.info("Screenshot saved: {} ({})", screenshotPath, message.getString());
        });
    }

    private static void capture(Minecraft client, JsonObject input) {
        switch (text(input, "action")) {
            case "start" -> {
                if (capture != null) {
                    throw new IllegalStateException("A live capture is already running");
                }
                Path path = Path.of(text(input, "path")).toAbsolutePath().normalize();
                if (!path.startsWith(output)) {
                    throw new IllegalArgumentException("Capture path must be inside automator.output");
                }
                capture = LiveCapture.start(client, new LiveCapture.Settings(path, Path.of(text(input, "ffmpeg")),
                        integer(input, "width"), integer(input, "height"), integer(input, "fps")));
                captureFrames = 0;
                captureSeconds = 0;
                captureDroppedFrames = 0;
                captureSource = capture.source();
            }
            case "stop" -> {
                if (capture == null) {
                    throw new IllegalStateException("No live capture is running");
                }
                LiveCapture finished = capture;
                capture = null;
                try {
                    finished.stop(client);
                } finally {
                    captureFrames = finished.frames();
                    captureSeconds = finished.seconds();
                    captureDroppedFrames = finished.droppedFrames();
                }
            }
            default -> throw new IllegalArgumentException("capture action must be start or stop");
        }
    }

    private static JsonObject snapshot(Minecraft client) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("ticks", ticks);
        result.addProperty("processId", ProcessHandle.current().pid());
        result.addProperty("frames", frames);
        result.addProperty("frameWidth", client.gameRenderer.mainRenderTarget().width);
        result.addProperty("frameHeight", client.gameRenderer.mainRenderTarget().height);
        result.addProperty("paused", client.isPaused());
        long windowFlags = SDLVideo.SDL_GetWindowFlags(client.getWindow().handle());
        result.addProperty("hiddenRenderer", HiddenRenderer.enabled());
        result.addProperty("windowVisible", (windowFlags & SDLVideo.SDL_WINDOW_HIDDEN) == 0);
        result.addProperty("windowFocused", (windowFlags & SDLVideo.SDL_WINDOW_INPUT_FOCUS) != 0);
        result.addProperty("windowActive", client.isWindowActive());
        result.addProperty("hudVisible", !client.gui.hud.isHidden());
        result.addProperty("mouseGrabbed", client.mouseHandler.isMouseGrabbed() || SDLMouse.SDL_GetWindowRelativeMouseMode(client.getWindow().handle())
            || SDLVideo.SDL_GetWindowMouseGrab(client.getWindow().handle()));
        result.addProperty("bridgeAttackActive", hasActiveAttackLease());
        result.addProperty("turning", turningPlayer != null);
        result.addProperty("cursorMoving", hasCursor() && System.nanoTime() - cursorStarted < cursorDuration);
        result.addProperty("windowPending", pendingWindow != null);
        result.addProperty("windowError", windowError);
        result.addProperty("lastError", lastError);
        result.addProperty("screenshotPath", screenshotPath);
        WormholesStatus.append(result);
        IrisStatus.append(result);
        JsonObject cursor = new JsonObject();
        cursor.addProperty("x", client.mouseHandler.getScaledXPos(client.getWindow()));
        cursor.addProperty("y", client.mouseHandler.getScaledYPos(client.getWindow()));
        result.add("cursor", cursor);
        Screen screen = client.gui.screen();
        result.addProperty("screen", screen == null ? null : screen.getClass().getName());
        result.addProperty("screenTitle", screen == null ? null : screen.getTitle().getString());
        LocalPlayer player = client.player;
        result.addProperty("connected", player != null && client.getConnection() != null);
        result.addProperty("capturing", capture != null);
        result.addProperty("captureFrames", capture != null ? capture.frames() : captureFrames);
        result.addProperty("captureSeconds", capture != null ? capture.seconds() : captureSeconds);
        result.addProperty("captureDroppedFrames", capture != null ? capture.droppedFrames() : captureDroppedFrames);
        result.addProperty("captureSource", captureSource);
        if (player != null) {
            result.addProperty("player", player.getName().getString());
            result.addProperty("uuid", player.getUUID().toString());
            result.add("position", vector(player.position()));
            result.add("velocity", vector(player.getDeltaMovement()));
            result.addProperty("health", player.getHealth());
            result.addProperty("onGround", player.onGround());
            result.addProperty("sprinting", player.isSprinting());
            result.addProperty("sneaking", player.isShiftKeyDown());
            result.addProperty("yaw", player.getYRot());
            result.addProperty("pitch", player.getXRot());
            PlayerSkin skin = player.getSkin();
            boolean loaded = !DEFAULT_SKIN_TEXTURES.contains(skin.body().texturePath());
            result.addProperty("skinLoaded", loaded);
            JsonObject skinState = new JsonObject();
            skinState.addProperty("loaded", loaded);
            skinState.addProperty("texture", skin.body().texturePath().toString());
            result.add("skin", skinState);
            Inventory inventory = player.getInventory();
            result.addProperty("selectedSlot", inventory.getSelectedSlot());
            result.add("heldItem", itemEntry(player.getMainHandItem()));
            JsonArray items = new JsonArray();
            for (int index = 0; index < inventory.getContainerSize(); index++) {
                ItemStack stack = inventory.getItem(index);
                if (!stack.isEmpty()) {
                    JsonObject entry = itemEntry(stack);
                    entry.addProperty("index", index);
                    items.add(entry);
                }
            }
            result.add("inventory", items);
            result.add("container", screen instanceof AbstractContainerScreen<?> containerScreen
                    ? container(player.containerMenu, containerScreen) : null);
        }
        return result;
    }

    private static Set<Identifier> defaultSkinTextures() {
        PlayerSkin[] skins = DefaultPlayerSkinAccessor.defaultSkins();
        Set<Identifier> textures = new HashSet<>(skins.length * 2);
        for (PlayerSkin skin : skins) {
            textures.add(skin.body().texturePath());
        }
        return Set.copyOf(textures);
    }

    private static JsonObject vector(Vec3 vector) {
        JsonObject result = new JsonObject();
        result.addProperty("x", vector.x);
        result.addProperty("y", vector.y);
        result.addProperty("z", vector.z);
        return result;
    }

    private static JsonObject container(AbstractContainerMenu menu, AbstractContainerScreen<?> screen) {
        JsonObject container = new JsonObject();
        container.addProperty("id", menu.containerId);
        container.addProperty("title", screen.getTitle().getString());
        JsonArray slots = new JsonArray();
        ContainerScreenAccessor position = (ContainerScreenAccessor) screen;
        for (Slot slot : menu.slots) {
            JsonObject entry = itemEntry(slot.getItem());
            entry.addProperty("index", slot.index);
            entry.addProperty("screenX", position.automatorLeftPos() + slot.x + 8);
            entry.addProperty("screenY", position.automatorTopPos() + slot.y + 8);
            slots.add(entry);
        }
        container.add("slots", slots);
        container.add("carried", itemEntry(menu.getCarried()));
        return container;
    }

    private static JsonObject itemEntry(ItemStack stack) {
        JsonObject item = new JsonObject();
        item.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        item.addProperty("count", stack.getCount());
        item.addProperty("name", stack.getHoverName().getString());
        JsonArray lore = new JsonArray();
        ItemLore component = stack.get(DataComponents.LORE);
        if (component != null) {
            for (Component line : component.lines()) {
                lore.add(line.getString());
            }
        }
        item.add("lore", lore);
        return item;
    }

    private static String text(JsonObject input, String name) {
        if (!input.has(name) || input.get(name).isJsonNull() || input.get(name).getAsString().isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return input.get(name).getAsString();
    }

    private static int integer(JsonObject input, String name) {
        if (!input.has(name) || input.get(name).isJsonNull()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return input.get(name).getAsInt();
    }

    private static int optionalInt(JsonObject input, String name, int fallback) {
        return input.has(name) && !input.get(name).isJsonNull() ? input.get(name).getAsInt() : fallback;
    }

    private record PendingWindow(AbstractContainerScreen<?> screen, int containerId, String action, int slot, int button, long due) {
    }
}
