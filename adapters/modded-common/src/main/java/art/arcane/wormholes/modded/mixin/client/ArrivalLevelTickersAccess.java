package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(Level.class)
public interface ArrivalLevelTickersAccess {
    @Accessor("blockEntityTickers")
    List<TickingBlockEntity> wormholes$blockEntityTickers();

    @Accessor("pendingBlockEntityTickers")
    List<TickingBlockEntity> wormholes$pendingBlockEntityTickers();
}
