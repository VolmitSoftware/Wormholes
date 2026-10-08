package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalRenderer;
import art.arcane.wormholes.modded.client.render.sodium.SodiumSectionDiscovery;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.joml.Vector3d;
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
    @Shadow private UniformBufferManager uniformBufferManager;
    @Shadow private FogParameters lastFogParameters;
    @Shadow private Vector3d lastCameraPos;
    @Shadow private int renderDistance;
    @Unique private long wormholes$sweptSection = Long.MIN_VALUE;
    @Unique private long wormholes$sweptAt;
    @Unique private boolean wormholes$built;
    @Unique private boolean wormholes$discoveryArmed;

    @Shadow
    protected abstract void processChunkEvents();

    @Override
    public RenderSectionManager wormholes$sections() {
        return renderSectionManager;
    }

    @Override
    public UniformBufferManager wormholes$uniforms() {
        return uniformBufferManager;
    }

    @Override
    public FogParameters wormholes$fog() {
        return lastFogParameters;
    }

    @Override
    public void wormholes$fog(FogParameters fog) {
        lastFogParameters = fog;
    }

    @Override
    public Vector3d wormholes$lastCamera() {
        return lastCameraPos;
    }

    @Override
    public void wormholes$lastCamera(Vector3d camera) {
        lastCameraPos = camera;
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
    public boolean wormholes$claimBuild() {
        if (wormholes$built) {
            return false;
        }
        wormholes$built = true;
        return true;
    }

    @Override
    public long wormholes$sweptSection() {
        return wormholes$sweptSection;
    }

    @Override
    public long wormholes$sweptAt() {
        return wormholes$sweptAt;
    }

    @Override
    public void wormholes$swept(long section, long nanos) {
        wormholes$sweptSection = section;
        wormholes$sweptAt = nanos;
    }

    @Override
    public boolean wormholes$discoveryArmed() {
        return wormholes$discoveryArmed;
    }

    @Override
    public void wormholes$discoveryArmed(boolean armed) {
        wormholes$discoveryArmed = armed;
    }

    @Inject(method = "endFrame", at = @At("HEAD"))
    private void wormholes$frameEnded(CallbackInfo callback) {
        wormholes$built = false;
    }

    @Inject(method = "setupTerrain", at = @At("TAIL"))
    private void wormholes$discoverAfterCrossing(Camera camera, Viewport viewport, FogParameters fog, boolean occlusion, boolean immediate,
                                                Matrix4f cullMatrix, CallbackInfo callback) {
        SodiumSectionDiscovery.terrainReady((SodiumWorldRenderer) (Object) this, viewport, fog);
    }

    @Inject(method = "setupTerrain", at = @At("HEAD"), cancellable = true)
    private void wormholes$keepSharedTerrain(Camera camera, Viewport viewport, FogParameters fog, boolean occlusion, boolean immediate,
                                             Matrix4f cullMatrix, CallbackInfo callback) {
        if (PortalStencilRenderer.instance().sharedLayer()) {
            callback.cancel();
        }
    }
}
