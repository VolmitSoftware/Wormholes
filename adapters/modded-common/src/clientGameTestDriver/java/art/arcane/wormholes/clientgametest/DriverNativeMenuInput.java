package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.clientgametest.drivermixin.DriverKeyboardInput;
import art.arcane.wormholes.clientgametest.drivermixin.DriverMouseInput;
import art.arcane.wormholes.clientgametest.menu.NativeMenuInput;
import art.arcane.wormholes.clientgametest.mixin.NativeMenuMouseAccess;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

final class DriverNativeMenuInput implements NativeMenuInput {
    private static final int CAPTURE_TIMEOUT_TICKS = 100;

    private final DriverClient client;

    DriverNativeMenuInput(DriverClient client) {
        this.client = client;
    }

    @Override
    public void runOnClient(Consumer<Minecraft> action) {
        client.runOnClient(action);
    }

    @Override
    public <T> T computeOnClient(Function<Minecraft, T> function) {
        return client.computeOnClient(function);
    }

    @Override
    public void waitTicks(int ticks) {
        client.waitTicks(ticks);
    }

    @Override
    public void waitFor(Predicate<Minecraft> condition, int timeoutTicks) {
        client.waitFor(condition, timeoutTicks);
    }

    @Override
    public void setCursorPos(double x, double y) {
        client.runOnClient(minecraft -> {
            DriverMouseInput mouse = (DriverMouseInput) minecraft.mouseHandler;
            mouse.wormholesTest$xpos(x);
            mouse.wormholesTest$ypos(y);
        });
    }

    @Override
    public void pressMouse(int button) {
        mouse(button, InputConstants.PRESS);
        client.waitTicks(1);
        mouse(button, InputConstants.RELEASE);
    }

    @Override
    public void holdShift() {
        key(InputConstants.KEY_LSHIFT, InputConstants.PRESS);
    }

    @Override
    public void releaseShift() {
        key(InputConstants.KEY_LSHIFT, InputConstants.RELEASE);
    }

    @Override
    public void typeChars(String text) {
        client.runOnClient(minecraft -> {
            DriverKeyboardInput keyboard = (DriverKeyboardInput) minecraft.keyboardHandler;
            long handle = minecraft.getWindow().handle();
            text.codePoints().forEach(codePoint -> keyboard.wormholesTest$charTyped(handle, new CharacterEvent(codePoint)));
        });
    }

    @Override
    public void pressKey(int keyCode) {
        key(keyCode, InputConstants.PRESS);
        client.waitTicks(1);
        key(keyCode, InputConstants.RELEASE);
    }

    @Override
    public void scroll(double amount) {
        client.runOnClient(minecraft -> ((DriverMouseInput) minecraft.mouseHandler).wormholesTest$onScroll(minecraft.getWindow().handle(), 0.0D, amount));
    }

    @Override
    public Path screenshot(String name, Path directory) {
        Path output = directory.resolve(name + ".png");
        AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
        client.runOnClient(minecraft -> FramebufferProbe.capture(minecraft, output, captured::set));
        client.waitFor(minecraft -> captured.get() != null, CAPTURE_TIMEOUT_TICKS);
        return output;
    }

    private void mouse(int button, int action) {
        client.runOnClient(minecraft -> ((NativeMenuMouseAccess) minecraft.mouseHandler)
            .nativeMenu$onButton(minecraft.getWindow().handle(), new MouseButtonInfo(button, 0), action));
    }

    private void key(int keyCode, int action) {
        client.runOnClient(minecraft -> ((DriverKeyboardInput) minecraft.keyboardHandler)
            .wormholesTest$keyPress(minecraft.getWindow().handle(), action, new KeyEvent(keyCode, 0, 0)));
    }
}
