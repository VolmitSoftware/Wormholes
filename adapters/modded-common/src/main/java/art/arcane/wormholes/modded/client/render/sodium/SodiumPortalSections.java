package art.arcane.wormholes.modded.client.render.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.tree.RemovableMultiForest;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.multiplayer.ClientLevel;

public interface SodiumPortalSections {
    SortedRenderLists wormholes$renderLists();

    void wormholes$renderLists(SortedRenderLists lists);

    SectionTree wormholes$renderTree();

    void wormholes$renderTree(SectionTree tree);

    void wormholes$taskLists(DeferredTaskList tasks);

    SectionStorage wormholes$storage();

    RemovableMultiForest wormholes$renderable();

    ClientLevel wormholes$level();

    boolean wormholes$culling();

    void wormholes$settleCulling();

    void wormholes$graphUpdated();

    float wormholes$searchDistance(FogParameters fog);

    float wormholes$buildDistance();
}
