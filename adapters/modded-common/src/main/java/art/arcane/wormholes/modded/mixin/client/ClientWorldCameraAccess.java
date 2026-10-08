package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Camera.class)
public interface ClientWorldCameraAccess {
    @Mutable
    @Accessor("attributeProbe")
    void wormholes$attributeProbe(EnvironmentAttributeProbe probe);
}
