package art.arcane.wormholes.clientgametest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import java.util.Objects;

public final class NativeSeamlessInput {
    private static LocalPlayer sourcePlayer;
    private static ClientLevel sourceLevel;
    private static double cursorX;
    private static double cursorY;
    private static double previousX;
    private static double previousY;
    private static long lastInterval = -1L;
    private static boolean continuous;

    private NativeSeamlessInput() {
    }

    static void begin(Minecraft minecraft) {
        clear();
        sourcePlayer = Objects.requireNonNull(minecraft.player, "turning source player");
        sourceLevel = Objects.requireNonNull(minecraft.level, "turning source level");
        continuous = true;
    }

    static void moveCursor(SeamlessClient client, double deltaX, double deltaY) {
        client.runOnClient(minecraft -> queue(minecraft, deltaX, deltaY));
    }

    public static void frame(Minecraft minecraft) {
        if (sourcePlayer == null) {
            return;
        }
        if (minecraft.player != sourcePlayer || minecraft.level != sourceLevel) {
            clear();
            return;
        }
        if (continuous && !SeamlessTurningTap.inputAllowed()) {
            return;
        }
        long interval = continuous ? SeamlessTurningTap.interval() : TravelTap.frameIndex();
        if (lastInterval == interval) {
            return;
        }
        double deltaX = cursorX;
        double deltaY = cursorY;
        cursorX = 0.0D;
        cursorY = 0.0D;
        if (deltaX == 0.0D && deltaY == 0.0D && continuous) {
            deltaX = -previousX;
            deltaY = -previousY;
        }
        if (deltaX == 0.0D && deltaY == 0.0D) {
            return;
        }
        lastInterval = interval;
        previousX = deltaX;
        previousY = deltaY;
        turn(minecraft, deltaX, deltaY);
    }

    static void clear() {
        sourcePlayer = null;
        sourceLevel = null;
        cursorX = 0.0D;
        cursorY = 0.0D;
        previousX = 0.0D;
        previousY = 0.0D;
        lastInterval = -1L;
        continuous = false;
    }

    private static void queue(Minecraft minecraft, double deltaX, double deltaY) {
        if (!SeamlessTurningTap.acceptsInput(minecraft)) {
            clear();
            throw new AssertionError("cursor input left its original crossing scope");
        }
        if (sourcePlayer == null) {
            sourcePlayer = Objects.requireNonNull(minecraft.player, "cursor source player");
            sourceLevel = Objects.requireNonNull(minecraft.level, "cursor source level");
        }
        if (minecraft.player != sourcePlayer || minecraft.level != sourceLevel) {
            clear();
            throw new AssertionError("cursor input left its original player or level");
        }
        cursorX += deltaX;
        cursorY += deltaY;
    }

    private static void turn(Minecraft minecraft, double deltaX, double deltaY) {
        LocalPlayer player = minecraft.player;
        Options options = minecraft.options;
        double sensitivity = options.sensitivity().get() * 0.6000000238418579D + 0.20000000298023224D;
        double scale = sensitivity * sensitivity * sensitivity;
        if (!options.getCameraType().isFirstPerson() || !player.isScoping()) {
            scale *= 8.0D;
        }
        double yaw = deltaX * scale * (options.invertMouseX().get() ? -1.0D : 1.0D);
        double pitch = deltaY * scale * (options.invertMouseY().get() ? -1.0D : 1.0D);
        player.turn(yaw, pitch);
    }
}
