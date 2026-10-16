package art.arcane.wormholes.clientgametest;

import net.minecraft.client.Minecraft;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

interface TestClient {
    void runOnClient(Consumer<Minecraft> action);

    <T> T computeOnClient(Function<Minecraft, T> function);

    void waitTicks(int ticks);

    void waitFor(Predicate<Minecraft> condition, int timeoutTicks);
}
