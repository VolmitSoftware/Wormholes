package art.arcane.wormholes.modded.mixin.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.client.GravityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public abstract class GravityRenderStateMixin implements GravityRenderState {
    @Unique private GravityFrame wormholes$gravity = GravityFrame.WORLD;

    @Override
    public GravityFrame wormholes$gravity() {
        return wormholes$gravity;
    }

    @Override
    public void wormholes$gravity(GravityFrame frame) {
        wormholes$gravity = frame;
    }
}
