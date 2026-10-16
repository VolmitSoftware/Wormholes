package art.arcane.wormholes.clientgametest.menu;

import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public interface NativeMenuInput {
    void runOnClient(Consumer<Minecraft> action);

    <T> T computeOnClient(Function<Minecraft, T> function);

    void waitTicks(int ticks);

    void waitFor(Predicate<Minecraft> condition, int timeoutTicks);

    void setCursorPos(double x, double y);

    void pressMouse(int button);

    void holdShift();

    void releaseShift();

    void typeChars(String text);

    void pressKey(int keyCode);

    void scroll(double amount);

    Path screenshot(String name, Path directory);
}
