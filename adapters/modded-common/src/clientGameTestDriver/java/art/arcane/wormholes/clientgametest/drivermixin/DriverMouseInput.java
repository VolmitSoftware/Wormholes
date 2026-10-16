package art.arcane.wormholes.clientgametest.drivermixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MouseHandler.class)
public interface DriverMouseInput {
    @Accessor("xpos")
    void wormholesTest$xpos(double x);

    @Accessor("ypos")
    void wormholesTest$ypos(double y);

    @Invoker("onScroll")
    void wormholesTest$onScroll(long handle, double x, double y);
}
