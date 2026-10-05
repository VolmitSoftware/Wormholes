package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
public interface IrisPortalHandAccess {
    @Accessor("bufferSource")
    RenderBuffers wormholes$buffers();

    @Mutable
    @Accessor("bufferSource")
    void wormholes$buffers(RenderBuffers value);

    @Accessor("cachedProjectionMatrixBuffer")
    ProjectionMatrixBuffer wormholes$projectionBuffer();

    @Mutable
    @Accessor("cachedProjectionMatrixBuffer")
    void wormholes$projectionBuffer(ProjectionMatrixBuffer value);

    @Accessor("submitNodeCollector")
    SubmitNodeStorage wormholes$submits();

    @Accessor("submitNodeCollector")
    void wormholes$submits(SubmitNodeStorage value);

    @Accessor("featureRenderDispatcher")
    FeatureRenderDispatcher wormholes$dispatcher();

    @Accessor("featureRenderDispatcher")
    void wormholes$dispatcher(FeatureRenderDispatcher value);

    @Accessor("projection")
    Projection wormholes$projection();

    @Accessor("projection")
    void wormholes$projection(Projection value);

    @Accessor("ACTIVE")
    boolean wormholes$active();

    @Accessor("ACTIVE")
    void wormholes$active(boolean value);

    @Accessor("renderingSolid")
    boolean wormholes$solid();

    @Accessor("renderingSolid")
    void wormholes$solid(boolean value);
}
