package art.arcane.wormholes.modded.client;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.item.ItemStack;

public interface LivingEntityUseState {
    EntityDataAccessor<Byte> wormholesLivingFlags();

    void wormholesUseItem(ItemStack item);

    void wormholesUseItemRemaining(int ticks);
}
