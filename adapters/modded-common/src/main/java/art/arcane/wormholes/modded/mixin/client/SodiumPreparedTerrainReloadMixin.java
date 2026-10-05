package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.client.render.PortalSodiumSectionAccess;
import art.arcane.wormholes.modded.client.render.PortalSodiumSectionState;
import art.arcane.wormholes.modded.client.render.PortalSodiumTerrainAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class SodiumPreparedTerrainReloadMixin implements PortalSodiumTerrainAccess {
    @Shadow private RenderSectionManager renderSectionManager;
    @Shadow private UniformBufferManager uniformBufferManager;

    @Override
    public RenderSectionManager wormholes$terrainManager() {
        return renderSectionManager;
    }

    @Override
    public void wormholes$prepareFrame() {
        uniformBufferManager.prepareFrame();
    }

    @Override
    public boolean wormholes$sectionSettled(int x, int y, int z) {
        RenderSection section = ((PortalSodiumSectionAccess) renderSectionManager).wormholes$terrainSection(x, y, z);
        return section == null || section.isBuilt() && ((PortalSodiumSectionState) section).wormholes$buildSettled();
    }

    @WrapMethod(method = "reload")
    private void wormholes$retainTerrain(Operation<Void> original) {
        if (!ClientSodiumTerrain.retainReload((SodiumWorldRenderer) (Object) this)) {
            original.call();
        }
    }
}
