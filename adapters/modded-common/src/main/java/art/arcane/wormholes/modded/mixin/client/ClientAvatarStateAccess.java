package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.entity.ClientAvatarState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientAvatarState.class)
public interface ClientAvatarStateAccess {
    @Accessor("xCloak")
    double wormholes$xCloak();

    @Accessor("xCloak")
    void wormholes$xCloak(double value);

    @Accessor("yCloak")
    double wormholes$yCloak();

    @Accessor("yCloak")
    void wormholes$yCloak(double value);

    @Accessor("zCloak")
    double wormholes$zCloak();

    @Accessor("zCloak")
    void wormholes$zCloak(double value);

    @Accessor("xCloakO")
    double wormholes$xCloakO();

    @Accessor("xCloakO")
    void wormholes$xCloakO(double value);

    @Accessor("yCloakO")
    double wormholes$yCloakO();

    @Accessor("yCloakO")
    void wormholes$yCloakO(double value);

    @Accessor("zCloakO")
    double wormholes$zCloakO();

    @Accessor("zCloakO")
    void wormholes$zCloakO(double value);
}
