package art.arcane.wormholes.clientgametest.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MouseHandler.class)
public interface NativeMenuMouseAccess {
    @Invoker("onButton")
    void nativeMenu$onButton(long handle, MouseButtonInfo button, int action);
}
