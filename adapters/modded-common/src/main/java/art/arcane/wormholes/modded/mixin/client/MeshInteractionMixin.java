package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientMeshEntities;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Predicate;

@Mixin(LocalPlayer.class)
public abstract class MeshInteractionMixin {
    @Unique
    private static final Predicate<Entity> WORMHOLES_INTERACTION_TARGET = ClientMeshEntities::interactionTarget;

    @Redirect(method = {"raycastHitResult", "pick"},
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/EntitySelector;CAN_BE_PICKED:Ljava/util/function/Predicate;"),
        require = 2)
    private static Predicate<Entity> wormholesInteractionTarget() {
        return WORMHOLES_INTERACTION_TARGET;
    }
}
