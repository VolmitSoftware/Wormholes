package art.arcane.wormholes.modded.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerPlayer.class)
public interface SeamlessPlayerAccess {
    @Accessor("enteredNetherPosition")
    void wormholesEnteredNetherPosition(Vec3 position);

    @Invoker("triggerDimensionChangeTriggers")
    void wormholesTriggerDimensionChange(ServerLevel from);
}
