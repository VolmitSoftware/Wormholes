package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.sodium.SodiumLayers;
import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalRenderer;
import art.arcane.wormholes.modded.client.render.sodium.SodiumSectionDiscovery;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class SodiumPortalRendererMixin implements SodiumPortalRenderer {
    @Shadow private RenderSectionManager renderSectionManager;
    @Shadow private int renderDistance;
    @Unique private int wormholes$builtEpoch = Integer.MIN_VALUE;
    @Unique private boolean wormholes$discoveryArmed;

    @Shadow
    protected abstract void processChunkEvents();

    @Override
    public RenderSectionManager wormholes$sections() {
        return renderSectionManager;
    }

    @Override
    public int wormholes$renderDistance() {
        return renderDistance;
    }

    @Override
    public void wormholes$processChunkEvents() {
        processChunkEvents();
    }

    @Override
    public boolean wormholes$claimBuild(int epoch) {
        if (wormholes$builtEpoch == epoch) {
            return false;
        }
        wormholes$builtEpoch = epoch;
        return true;
    }

    @Override
    public boolean wormholes$discoveryArmed() {
        return wormholes$discoveryArmed;
    }

    @Override
    public void wormholes$discoveryArmed(boolean armed) {
        wormholes$discoveryArmed = armed;
    }

    @Inject(method = "setupTerrain", at = @At("HEAD"), cancellable = true)
    private void wormholes$keepLayerTerrain(Camera camera, Viewport viewport, int frame, boolean spectator, boolean immediate,
                                            CallbackInfo callback) {
        if (SodiumLayers.slot() > 0) {
            callback.cancel();
            return;
        }
        SodiumLayers.frameStarted();
    }

    @Inject(method = "setupTerrain", at = @At("TAIL"))
    private void wormholes$discoverAfterCrossing(Camera camera, Viewport viewport, int frame, boolean spectator, boolean immediate,
                                                CallbackInfo callback) {
        SodiumSectionDiscovery.terrainReady((SodiumWorldRenderer) (Object) this, camera, viewport, spectator);
    }
}
