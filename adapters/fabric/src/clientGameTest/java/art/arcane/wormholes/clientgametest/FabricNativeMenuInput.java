package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.clientgametest.menu.NativeMenuInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

final class FabricNativeMenuInput implements NativeMenuInput {
    private final ClientGameTestContext context;

    FabricNativeMenuInput(ClientGameTestContext context) {
        this.context = context;
    }

    @Override
    public void runOnClient(Consumer<Minecraft> action) {
        context.runOnClient(action::accept);
    }

    @Override
    public <T> T computeOnClient(Function<Minecraft, T> function) {
        return context.computeOnClient(function::apply);
    }

    @Override
    public void waitTicks(int ticks) {
        context.waitTicks(ticks);
    }

    @Override
    public void waitFor(Predicate<Minecraft> condition, int timeoutTicks) {
        context.waitFor(condition, timeoutTicks);
    }

    @Override
    public void setCursorPos(double x, double y) {
        context.getInput().setCursorPos(x, y);
    }

    @Override
    public void pressMouse(int button) {
        context.getInput().pressMouse(button);
    }

    @Override
    public void holdShift() {
        context.getInput().holdShift();
    }

    @Override
    public void releaseShift() {
        context.getInput().releaseShift();
    }

    @Override
    public void typeChars(String text) {
        context.getInput().typeChars(text);
    }

    @Override
    public void pressKey(int keyCode) {
        context.getInput().pressKey(keyCode);
    }

    @Override
    public void scroll(double amount) {
        context.getInput().scroll(amount);
    }

    @Override
    public Path screenshot(String name, Path directory) {
        return context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(directory));
    }
}
