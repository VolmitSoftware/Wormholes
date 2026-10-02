package art.arcane.wormholes.modded.mixin;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.server.level.ServerPlayer$RespawnPosAngle")
public interface EndRespawnPositionAccessor {
    @Accessor("position")
    Vec3 wormholes$position();
}
