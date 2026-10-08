package art.arcane.wormholes.modded.mixin.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
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

    @Accessor("featureRenderDispatcher")
    FeatureRenderDispatcher wormholes$features();

    @Mutable
    @Accessor("featureRenderDispatcher")
    void wormholes$features(FeatureRenderDispatcher features);

    @Mutable
    @Accessor("visibleSections")
    void wormholes$visibleSections(ObjectArrayList<SectionRenderDispatcher.RenderSection> sections);

    @Mutable
    @Accessor("nearbyVisibleSections")
    void wormholes$nearbyVisibleSections(ObjectArrayList<SectionRenderDispatcher.RenderSection> sections);

    @Accessor("currentFrameRendersEntityOutline")
    boolean wormholes$entityOutline();

    @Accessor("currentFrameRendersEntityOutline")
    void wormholes$entityOutline(boolean value);
}
