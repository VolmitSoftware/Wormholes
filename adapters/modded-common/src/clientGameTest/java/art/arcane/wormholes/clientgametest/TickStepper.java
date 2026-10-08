package art.arcane.wormholes.clientgametest;

import net.minecraft.client.Minecraft;

import java.util.function.Function;

interface TickStepper extends AutoCloseable {
    <T> T step(Function<Minecraft, T> sample);

    @Override
    void close();
}
