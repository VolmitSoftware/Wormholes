package art.arcane.automator;

import art.arcane.automator.mixin.ContainerScreenAccessor;
import art.arcane.automator.mixin.DefaultPlayerSkinAccessor;
import art.arcane.automator.mixin.KeyMappingAccessor;
import art.arcane.automator.mixin.SignEditScreenAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
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
import org.lwjgl.sdl.SDLKeycode;
import org.lwjgl.sdl.SDLScancode;
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
            "swapHands", "drop", "inventory", "playerList");
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
    private static PendingGui pendingGui;
    private static PendingText pendingText;
    private static LiveCapture capture;
    private static long captureFrames;
    private static long captureEncodedFrames;
    private static long captureRepeatedFrames;
    private static long captureMaxGapFrames;
    private static List<LiveCapture.HoldRange> captureHolds = List.of();
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
        applyGui(client);
        applyText(client);
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
        return cursorScreen != null && screen == cursorScreen;
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
                pendingGui = null;
                pendingText = null;
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
            case "clear-chat" -> client.gui.hud.getChat().clearMessages(false);
            case "pause-screen" -> {
                requirePlayer(client);
                client.pauseGame(false);
            }
            case "chat-screen" -> {
                requirePlayer(client);
                client.gui.setScreen(new ChatScreen(input.has("text") ? text(input, "text") : "", true));
            }
            case "gui" -> gui(client, input);
            case "sign-text" -> signText(client, input);
            case "anvil-name" -> anvilName(client, input);
            case "server-list" -> serverList(client, input);
            case "cursor" -> cursor(client, input);
            case "window" -> window(client, input);
            case "dismiss" -> {
                pendingWindow = null;
                pendingGui = null;
                pendingText = null;
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
            case "playerList" -> client.options.keyPlayerList;
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
        Screen screen = requireScreen(client, input);
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

    private static void moveCursor(Minecraft client, Screen screen, double x, double y, int duration) {
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

    private static void requireIdleGui() {
        if (pendingText != null || pendingGui != null || pendingWindow != null) {
            throw new IllegalStateException("Wait for pending GUI input before sending another");
        }
    }

    private static Screen requireScreen(Minecraft client, JsonObject input) {
        Screen screen = client.gui.screen();
        if (screen == null) {
            throw new IllegalStateException("No GUI screen is open");
        }
        if (input.has("screen") && !screen.getClass().getName().equals(text(input, "screen"))) {
            throw new IllegalStateException("GUI screen changed before input");
        }
        if (input.has("containerId")) {
            requireContainer(client, input);
        }
        return screen;
    }

    private static void gui(Minecraft client, JsonObject input) {
        Screen screen = requireScreen(client, input);
        if (pendingGui != null || pendingText != null || pendingWindow != null) {
            throw new IllegalStateException("Wait for pending GUI input before sending another");
        }
        switch (text(input, "action")) {
            case "list" -> { }
            case "close" -> screen.onClose();
            case "click" -> {
                int button = optionalInt(input, "button", 0);
                if (button != 0 && button != 1) {
                    throw new IllegalArgumentException("button must be 0 or 1");
                }
                cursor(client, input);
                pendingGui = new PendingGui(screen, button, cursorStarted + cursorDuration + TICK_NANOS);
            }
            case "key" -> pressGuiKey(screen, text(input, "key"));
            case "text" -> beginText(screen, input);
            default -> throw new IllegalArgumentException("GUI action must be list, close, click, key or text");
        }
    }

    private static void applyGui(Minecraft client) {
        PendingGui pending = pendingGui;
        if (pending == null || System.nanoTime() < pending.due()) {
            return;
        }
        pendingGui = null;
        try {
            if (client.gui.screen() != pending.screen()) {
                throw new IllegalStateException("GUI changed during cursor travel");
            }
            MouseButtonEvent event = new MouseButtonEvent(cursorX(), cursorY(), new MouseButtonInfo(GuiMouseButton.fromProtocol(pending.button()), 0));
            pending.screen().mouseMoved(event.x(), event.y());
            pending.screen().mouseClicked(event, false);
            pending.screen().mouseReleased(event);
        } catch (RuntimeException failure) {
            lastError = failure.toString();
            LOGGER.error("GUI input failed after cursor travel", failure);
        }
    }

    private static void pressGuiKey(Screen screen, String key) {
        KeyEvent event = switch (key) {
            case "enter" -> new KeyEvent(SDLScancode.SDL_SCANCODE_RETURN, SDLKeycode.SDLK_RETURN, 0);
            case "escape" -> new KeyEvent(SDLScancode.SDL_SCANCODE_ESCAPE, SDLKeycode.SDLK_ESCAPE, 0);
            case "tab" -> new KeyEvent(SDLScancode.SDL_SCANCODE_TAB, SDLKeycode.SDLK_TAB, 0);
            case "up" -> new KeyEvent(SDLScancode.SDL_SCANCODE_UP, SDLKeycode.SDLK_UP, 0);
            case "down" -> new KeyEvent(SDLScancode.SDL_SCANCODE_DOWN, SDLKeycode.SDLK_DOWN, 0);
            case "home" -> new KeyEvent(SDLScancode.SDL_SCANCODE_HOME, SDLKeycode.SDLK_HOME, 0);
            case "end" -> new KeyEvent(SDLScancode.SDL_SCANCODE_END, SDLKeycode.SDLK_END, 0);
            case "backspace" -> new KeyEvent(SDLScancode.SDL_SCANCODE_BACKSPACE, SDLKeycode.SDLK_BACKSPACE, 0);
            case "select-all" -> new KeyEvent(SDLScancode.SDL_SCANCODE_A, SDLKeycode.SDLK_A,
                    SDLKeycode.SDL_KMOD_CTRL | SDLKeycode.SDL_KMOD_GUI);
            default -> throw new IllegalArgumentException("Unsupported GUI key " + key);
        };
        screen.keyPressed(event);
        screen.keyReleased(event);
    }

    private static void beginText(Screen screen, JsonObject input) {
        if (pendingText != null || pendingGui != null || pendingWindow != null) {
            throw new IllegalStateException("Wait for pending GUI input before typing");
        }
        TextInputPlan plan = TextInputPlan.fromJson(input);
        if (!(screen instanceof AbstractSignEditScreen) && !(screen.getFocused() instanceof EditBox)) {
            throw new IllegalStateException("A sign or focused text field is required");
        }
        if (!input.has("replace") || input.get("replace").getAsBoolean()) {
            pressGuiKey(screen, "select-all");
            pressGuiKey(screen, "backspace");
        }
        pendingText = new PendingText(screen, plan, ticks);
    }

    private static void applyText(Minecraft client) {
        PendingText pending = pendingText;
        if (pending == null || ticks < pending.nextTick) {
            return;
        }
        try {
            if (client.gui.screen() != pending.screen) {
                throw new IllegalStateException("GUI changed during text input");
            }
            if (pending.index >= pending.plan.text().length()) {
                pendingText = null;
                return;
            }
            int codepoint = pending.plan.text().codePointAt(pending.index);
            pending.screen.charTyped(new CharacterEvent(codepoint));
            pending.index += Character.charCount(codepoint);
            pending.nextTick = ticks + pending.plan.ticksPerChar();
        } catch (RuntimeException failure) {
            pendingText = null;
            lastError = failure.toString();
            LOGGER.error("GUI text input failed", failure);
        }
    }

    private static void signText(Minecraft client, JsonObject input) {
        requireIdleGui();
        Screen screen = requireScreen(client, input);
        if (!(screen instanceof AbstractSignEditScreen)) {
            throw new IllegalStateException("No sign editor is open");
        }
        int line = optionalInt(input, "line", 0);
        if (line < 0 || line > 3) {
            throw new IllegalArgumentException("Sign line must be between 0 and 3");
        }
        SignEditScreenAccessor sign = (SignEditScreenAccessor) screen;
        for (int step = 0; step < 4 && sign.automatorLine() != line; step++) {
            pressGuiKey(screen, "down");
        }
        beginText(screen, input);
    }

    private static void anvilName(Minecraft client, JsonObject input) {
        requireIdleGui();
        Screen screen = requireScreen(client, input);
        if (!(screen instanceof AnvilScreen)) {
            throw new IllegalStateException("No anvil is open");
        }
        for (GuiEventListener child : screen.children()) {
            if (child instanceof EditBox box && box.visible && box.active) {
                screen.setFocused(box);
                beginText(screen, input);
                return;
            }
        }
        throw new IllegalStateException("The anvil name field is unavailable");
    }

    private static void serverList(Minecraft client, JsonObject input) {
        if (client.level != null || client.getConnection() != null) {
            throw new IllegalStateException("Disconnect before opening the server list");
        }
        String address = text(input, "address");
        if (!address.matches("127\\.0\\.0\\.1:[0-9]{1,5}")) {
            throw new IllegalArgumentException("Only explicit IPv4 loopback servers are accepted");
        }
        JoinMultiplayerScreen screen = new JoinMultiplayerScreen(new TitleScreen());
        client.gui.setScreen(screen);
        while (screen.getServers().size() > 0) {
            screen.getServers().remove(screen.getServers().get(0));
        }
        screen.getServers().add(new ServerData(input.has("name") ? text(input, "name") : "Demo server", address,
                ServerData.Type.OTHER), false);
        for (GuiEventListener child : screen.children()) {
            if (child instanceof ServerSelectionList list) {
                list.updateOnlineServers(screen.getServers());
            }
        }
    }

    private static JsonArray guiWidgets(Screen screen) {
        JsonArray widgets = new JsonArray();
        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof AbstractWidget widget) || !widget.visible) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("type", widget.getClass().getSimpleName());
            entry.addProperty("label", widget.getMessage().getString());
            entry.addProperty("active", widget.active);
            entry.addProperty("x", widget.getX());
            entry.addProperty("y", widget.getY());
            entry.addProperty("width", widget.getWidth());
            entry.addProperty("height", widget.getHeight());
            if (widget instanceof EditBox box) {
                entry.addProperty("value", box.getValue());
            }
            widgets.add(entry);
        }
        return widgets;
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
                captureEncodedFrames = 0;
                captureRepeatedFrames = 0;
                captureMaxGapFrames = 0;
                captureHolds = List.of();
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
                    captureEncodedFrames = finished.encodedFrames();
                    captureRepeatedFrames = finished.repeatedFrames();
                    captureMaxGapFrames = finished.maxGapFrames();
                    captureHolds = finished.holds();
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
        long window = client.getWindow().handle();
        long flags = SDLVideo.SDL_GetWindowFlags(window);
        result.addProperty("hiddenRenderer", HiddenRenderer.enabled());
        result.addProperty("windowVisible", (flags & SDLVideo.SDL_WINDOW_HIDDEN) == 0);
        result.addProperty("windowFocused", (flags & SDLVideo.SDL_WINDOW_INPUT_FOCUS) != 0);
        result.addProperty("windowActive", client.isWindowActive());
        result.addProperty("windowMouseGrabbed", SDLVideo.SDL_GetWindowMouseGrab(window));
        result.addProperty("relativeMouseMode", SDLMouse.SDL_GetWindowRelativeMouseMode(window));
        result.addProperty("hudVisible", !client.gui.hud.isHidden());
        result.addProperty("mouseGrabbed", client.mouseHandler.isMouseGrabbed() || SDLMouse.SDL_GetWindowRelativeMouseMode(window)
            || SDLVideo.SDL_GetWindowMouseGrab(window));
        result.addProperty("bridgeAttackActive", hasActiveAttackLease());
        result.addProperty("turning", turningPlayer != null);
        result.addProperty("cursorMoving", hasCursor() && System.nanoTime() - cursorStarted < cursorDuration);
        result.addProperty("windowPending", pendingWindow != null);
        result.addProperty("guiPending", pendingGui != null);
        result.addProperty("textPending", pendingText != null);
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
        if (screen != null) {
            result.addProperty("guiWidth", screen.width);
            result.addProperty("guiHeight", screen.height);
            result.add("guiWidgets", guiWidgets(screen));
        }
        LocalPlayer player = client.player;
        result.addProperty("connected", player != null && client.getConnection() != null);
        result.addProperty("capturing", capture != null);
        result.addProperty("captureFrames", capture != null ? capture.frames() : captureFrames);
        result.addProperty("captureEncodedFrames", capture != null ? capture.encodedFrames() : captureEncodedFrames);
        result.addProperty("captureRepeatedFrames", capture != null ? capture.repeatedFrames() : captureRepeatedFrames);
        result.addProperty("captureMaxGapFrames", capture != null ? capture.maxGapFrames() : captureMaxGapFrames);
        JsonArray holds = new JsonArray();
        for (LiveCapture.HoldRange hold : capture != null ? capture.holds() : captureHolds) {
            JsonObject range = new JsonObject();
            range.addProperty("startFrame", hold.startFrame());
            range.addProperty("endFrame", hold.endFrame());
            holds.add(range);
        }
        result.add("captureHolds", holds);
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

    private record PendingGui(Screen screen, int button, long due) {
    }

    private static final class PendingText {
        private final Screen screen;
        private final TextInputPlan plan;
        private int index;
        private long nextTick;

        private PendingText(Screen screen, TextInputPlan plan, long nextTick) {
            this.screen = screen;
            this.plan = plan;
            this.nextTick = nextTick;
        }
    }

    private record PendingWindow(AbstractContainerScreen<?> screen, int containerId, String action, int slot, int button, long due) {
    }
}
