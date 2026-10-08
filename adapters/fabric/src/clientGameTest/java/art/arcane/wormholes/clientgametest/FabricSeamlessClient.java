package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.minecraft.client.Minecraft;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

final class FabricSeamlessClient implements SeamlessClient {
    private final ClientGameTestContext context;
    private final TestServerConnection connection;

    FabricSeamlessClient(ClientGameTestContext context, TestServerConnection connection) {
        this.context = context;
        this.connection = connection;
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
    public void waitForChunksDownload() {
        connection.waitForChunksDownload();
    }

    @Override
    public void waitForChunksRender() {
        connection.waitForChunksRender();
    }

    @Override
    public void holdForward() {
        context.getInput().holdKey(options -> options.keyUp);
    }

    @Override
    public void releaseForward() {
        context.getInput().releaseKey(options -> options.keyUp);
    }

    @Override
    public void holdForwardFor(int ticks) {
        context.getInput().holdKeyFor(options -> options.keyUp, ticks);
    }

    @Override
    public void lookAt(float yaw, float pitch) {
        context.getInput().lookAt(yaw, pitch);
    }

    @Override
    public void moveCursor(double deltaX, double deltaY) {
        context.getInput().moveCursor(deltaX, deltaY);
    }

    @Override
    public void restoreDefaultGameOptions() {
        context.restoreDefaultGameOptions();
    }

    @Override
    public TickStepper lockstep() {
        return ServerPacketDelivery.stepper(this);
    }
}
