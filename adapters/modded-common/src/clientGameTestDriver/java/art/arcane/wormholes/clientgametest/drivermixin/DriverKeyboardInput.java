package art.arcane.wormholes.clientgametest.drivermixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(KeyboardHandler.class)
public interface DriverKeyboardInput {
    @Invoker("keyPress")
    void wormholesTest$keyPress(long handle, int action, KeyEvent event);

    @Invoker("charTyped")
    void wormholesTest$charTyped(long handle, CharacterEvent event);
}
