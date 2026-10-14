package art.arcane.wormholes.clientgametest.drivermixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Window.class)
public interface DriverWindowSize {
    @Accessor("width")
    void wormholesTest$width(int width);

    @Accessor("height")
    void wormholesTest$height(int height);

    @Accessor("framebufferWidth")
    void wormholesTest$framebufferWidth(int width);

    @Accessor("framebufferHeight")
    void wormholesTest$framebufferHeight(int height);

    @Invoker("setMode")
    void wormholesTest$setMode();
}
