package art.arcane.wormholes.gametest.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.WorldLoader;
import net.minecraft.world.level.dimension.LevelStem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// The 1.20.1 GameTestServer bakes its world from an empty dimension registry; use the datapack dimensions as a dedicated server
// does, so the Wormholes pocket dimension exists in GameTests.
@Mixin(GameTestServer.class)
public abstract class GameTestDimensionsMixin {
    @ModifyArg(method = "*", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/levelgen/WorldDimensions;bake(Lnet/minecraft/core/Registry;)Lnet/minecraft/world/level/levelgen/WorldDimensions$Complete;"))
    private static Registry<LevelStem> wormholesDatapackDimensions(Registry<LevelStem> empty,
                                                                   @Local(argsOnly = true) WorldLoader.DataLoadContext context) {
        return context.datapackDimensions().registryOrThrow(Registries.LEVEL_STEM);
    }
}
