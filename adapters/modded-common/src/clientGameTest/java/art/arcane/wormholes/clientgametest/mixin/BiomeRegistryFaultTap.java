package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.wormholes.clientgametest.BiomeRegistryFaults;
import art.arcane.wormholes.modded.client.ClientMeshSections;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = ClientMeshSections.class, remap = false)
public abstract class BiomeRegistryFaultTap {
    @ModifyVariable(method = "put", at = @At("HEAD"), argsOnly = true)
    private ViewStreamMessage.MeshSection wormholes$biomeRegistryFault(ViewStreamMessage.MeshSection message) {
        return BiomeRegistryFaults.apply(message);
    }
}
