package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

final class FabricTestClient implements TestClient {
    private final ClientGameTestContext context;

    FabricTestClient(ClientGameTestContext context) {
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
}
