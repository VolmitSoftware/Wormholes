package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

@Mixin(Mannequin.class)
public interface ReflectionDataAccessor {
    @Accessor("DATA_PROFILE")
    static EntityDataAccessor<ResolvableProfile> wormholesProfile() {
        throw new AssertionError("mixin accessor");
    }

    @Accessor("DATA_DESCRIPTION")
    static EntityDataAccessor<Optional<Component>> wormholesDescription() {
        throw new AssertionError("mixin accessor");
    }
}
