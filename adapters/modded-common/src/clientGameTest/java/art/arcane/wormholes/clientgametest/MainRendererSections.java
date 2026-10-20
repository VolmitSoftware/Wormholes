package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalRenderer;
import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalSections;
import art.arcane.wormholes.modded.client.render.stencil.PortalBackends;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

final class MainRendererSections {
    private MainRendererSections() {
    }

    static int visible(Minecraft minecraft) {
        if (!PortalBackends.sodium()) {
            return minecraft.levelRenderer.visibleSections().size();
        }
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
        return renderer == null ? 0 : renderer.getVisibleChunkCount();
    }

    static boolean terrainComplete(Minecraft minecraft) {
        if (!PortalBackends.sodium()) {
            return minecraft.levelRenderer.hasRenderedAllSections();
        }
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
        return renderer != null && renderer.isTerrainRenderComplete();
    }

    static int missingReadySections(Minecraft minecraft) {
        if (!PortalBackends.sodium()) {
            return 0;
        }
        ChunkTracker tracker = ChunkTrackerHolder.get(minecraft.level);
        SodiumWorldRenderer renderer = ((LevelRendererExtension) minecraft.levelRenderer).sodium$getWorldRenderer();
        RenderSectionManager sections = renderer == null ? null : ((SodiumPortalRenderer) renderer).wormholes$sections();
        SectionStorage storage = sections == null ? null : ((SodiumPortalSections) sections).wormholes$storage();
        LongIterator ready = tracker.getReadyChunks().iterator();
        int missing = 0;
        while (ready.hasNext()) {
            long position = ready.nextLong();
            int x = ChunkPos.getX(position);
            int z = ChunkPos.getZ(position);
            for (int y = minecraft.level.getMinSectionY(); y <= minecraft.level.getMaxSectionY(); y++) {
                if (storage == null || !storage.hasSectionConsistent(SectionPos.asLong(x, y, z))) {
                    missing++;
                }
            }
        }
        return missing;
    }
}
