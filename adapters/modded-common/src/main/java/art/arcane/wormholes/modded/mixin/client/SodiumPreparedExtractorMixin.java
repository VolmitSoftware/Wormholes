package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LevelExtractor.class)
public abstract class SodiumPreparedExtractorMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private LevelRenderer levelRenderer;
    @Shadow private boolean shouldInvalidateCompiledGeometry;
    @Shadow private boolean shouldResetLevelRenderData;

    @WrapMethod(method = "setLevel")
    private void wormholes$restoreTerrain(ClientLevel level, Operation<Void> original) {
        if (ClientWorldLoader.switching()) {
            original.call(level);
            return;
        }
        boolean previousInvalidation = shouldInvalidateCompiledGeometry;
        ClientSodiumTerrain.beforeLevelChange(level);
        original.call(level);
        if (ClientSodiumTerrain.retainLevelInvalidation(level)) {
            if (shouldResetLevelRenderData) {
                levelRenderer.resetLevelRenderData();
                shouldResetLevelRenderData = false;
            }
            levelRenderer.invalidateCompiledGeometry(level, minecraft.options, minecraft.gameRenderer.mainCamera(), minecraft.getBlockColors());
            shouldInvalidateCompiledGeometry = previousInvalidation;
        }
    }
}
