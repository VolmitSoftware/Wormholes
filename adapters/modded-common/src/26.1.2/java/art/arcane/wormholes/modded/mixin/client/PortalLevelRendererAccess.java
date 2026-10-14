package art.arcane.wormholes.modded.mixin.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface PortalLevelRendererAccess {
    @Accessor("levelRenderState")
    LevelRenderState wormholes$portalState();

    @Accessor("level")
    ClientLevel wormholes$portalLevel();

    @Accessor("targets")
    LevelTargetBundle wormholes$targets();

    @Mutable
    @Accessor("targets")
    void wormholes$targets(LevelTargetBundle targets);

    @Accessor("submitNodeStorage")
    SubmitNodeStorage wormholes$submits();

    @Mutable
    @Accessor("submitNodeStorage")
    void wormholes$submits(SubmitNodeStorage submits);

    @Accessor("renderBuffers")
    RenderBuffers wormholes$renderBuffers();

    @Mutable
    @Accessor("renderBuffers")
    void wormholes$renderBuffers(RenderBuffers buffers);

    @Accessor("featureRenderDispatcher")
    FeatureRenderDispatcher wormholes$features();

    @Mutable
    @Accessor("featureRenderDispatcher")
    void wormholes$features(FeatureRenderDispatcher features);

    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> wormholes$visibleSections();

    @Mutable
    @Accessor("visibleSections")
    void wormholes$visibleSections(ObjectArrayList<SectionRenderDispatcher.RenderSection> sections);

    @Accessor("nearbyVisibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> wormholes$nearbyVisibleSections();

    @Mutable
    @Accessor("nearbyVisibleSections")
    void wormholes$nearbyVisibleSections(ObjectArrayList<SectionRenderDispatcher.RenderSection> sections);

}
