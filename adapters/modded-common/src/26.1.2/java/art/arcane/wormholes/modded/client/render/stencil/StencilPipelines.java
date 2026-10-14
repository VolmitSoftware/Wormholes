package art.arcane.wormholes.modded.client.render.stencil;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

public final class StencilPipelines {
    private static final ColorTargetState NO_COLOR = new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_NONE);
    private static final DepthStencilState TESTED = new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false);
    private static final DepthStencilState WRITTEN = new DepthStencilState(CompareOp.ALWAYS_PASS, true);
    public static final RenderPipeline MASK = aperture("mask", VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION, TESTED);
    public static final RenderPipeline MASK_SHAPED = aperture("mask_shaped", VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX, TESTED);
    public static final RenderPipeline DEPTH = aperture("depth", VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION, WRITTEN);
    public static final RenderPipeline DEPTH_SHAPED = aperture("depth_shaped", VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX, WRITTEN);
    public static final RenderPipeline FAR = fill("far", NO_COLOR, Optional.of(WRITTEN));
    public static final RenderPipeline CLAMP = fill("clamp", NO_COLOR, Optional.empty());
    public static final RenderPipeline CLEAR = fill("clear", ColorTargetState.DEFAULT, Optional.of(WRITTEN));
    public static final List<RenderPipeline> ALL = List.of(MASK, MASK_SHAPED, DEPTH, DEPTH_SHAPED, FAR, CLAMP, CLEAR);

    private StencilPipelines() {
    }

    public static RenderPipeline mask(boolean shaped) {
        return shaped ? MASK_SHAPED : MASK;
    }

    public static RenderPipeline depth(boolean shaped) {
        return shaped ? DEPTH_SHAPED : DEPTH;
    }

    private static RenderPipeline aperture(String name, VertexFormat.Mode mode, VertexFormat format, DepthStencilState depth) {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("wormholes", "pipeline/portal_stencil_" + name))
            .withVertexShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_stencil_aperture"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_stencil_aperture"))
            .withUniform("Projection", UniformType.UNIFORM_BUFFER).withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withColorTargetState(NO_COLOR).withDepthStencilState(depth).withCull(false)
            .withVertexFormat(format, mode).build();
    }

    private static RenderPipeline fill(String name, ColorTargetState color, Optional<DepthStencilState> depth) {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("wormholes", "pipeline/portal_stencil_" + name))
            .withVertexShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_stencil_fill"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_stencil_fill"))
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withColorTargetState(color).withDepthStencilState(depth).withCull(false)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build();
    }
}
