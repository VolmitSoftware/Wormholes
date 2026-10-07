package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;

final class MainRendererSections {
    private MainRendererSections() {
    }

    static int visible(Minecraft minecraft) {
        if (!ClientSodiumTerrain.available()) {
            return minecraft.levelRenderer.visibleSections().size();
        }
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
        return renderer == null ? 0 : renderer.getVisibleChunkCount();
    }
}
