package art.arcane.wormholes.modded.client.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;

/**
 * The renderer that shades streamed portal views: the owner of the shading role on the streamed-mesh path, as a
 * {@code PipelineBackend} is on the stencil path. It decides the colour format of the portal targets, whether it
 * draws the destination sky itself, and which shade mode a view gets; the renderer-specific work (pass redirection,
 * shader variants) happens behind {@link #prepare()}.
 */
public interface StreamedShading {
    int SHADE_VANILLA = 0;
    int SHADE_LIT_SHADOWED = 1;
    int SHADE_LIT = 2;
    /** Lit by a frame that is not the camera's (another dimension's light written by {@link #writeFrame}), exposed as that frame says. */
    int SHADE_LIT_FOREIGN = 3;

    /** No renderer shades streamed views: the game's lightmapped colour in RGBA8 targets and vanilla's sky. */
    StreamedShading NONE = new StreamedShading() {
        @Override
        public String name() {
            return "vanilla";
        }

        @Override
        public void prepare() {
        }

        @Override
        public boolean active() {
            return false;
        }

        @Override
        public GpuFormat colorFormat() {
            return GpuFormat.RGBA8_UNORM;
        }

        @Override
        public int skyKind(DimensionType.Skybox camera, DimensionType.Skybox destination) {
            return SKY_NONE;
        }

        @Override
        public int shadeMode(boolean sameLevel, DimensionType.Skybox camera, DimensionType.Skybox destination, int depth) {
            return SHADE_VANILLA;
        }

        @Override
        public void writeClouds(ByteBuffer out, double cameraX, double cameraY, double cameraZ) {
            for (int offset = 0; offset < CLOUD_BYTES; offset += Float.BYTES) {
                out.putFloat(0.0f);
            }
        }

        @Override
        public void bindSky(RenderPass pass) {
        }

        @Override
        public boolean shadesWater() {
            return false;
        }

        @Override
        public void writeFrame(ByteBuffer out, Matrix4fc view, Matrix4fc projection, double cameraX, double cameraY, double cameraZ, int width, int height,
                               DimensionType.Skybox destination) {
            for (int offset = 0; offset < FRAME_BYTES; offset += Float.BYTES) {
                out.putFloat(0.0f);
            }
        }

        @Override
        public void captureScene() {
        }
    };

    /** {@link #skyKind}: vanilla's own sky. */
    int SKY_NONE = 0;
    /** {@link #skyKind}: the renderer's overworld sky, its clouds marched per pixel. */
    int SKY_OVERWORLD = 1;
    /** {@link #skyKind}: the renderer's End sky. */
    int SKY_END = 2;
    /** The size of the renderer's per-frame state block a view carries, {@link #writeFrame} fills exactly this many bytes. */
    int FRAME_BYTES = 1024;
    /** The size of the renderer's cloud parameter block a view carries, {@link #writeClouds} fills exactly this many bytes. */
    int CLOUD_BYTES = 448;

    String name();

    /** Installs the renderer's hooks; render thread, before the portal pipelines compile, safe to repeat. */
    void prepare();

    /** Whether the renderer shades this frame's streamed views. Render thread. */
    boolean active();

    /** The colour format of the portal targets this frame. Render thread. */
    GpuFormat colorFormat();

    /**
     * Which sky the renderer draws through {@code wormholes:portal_sky} in place of vanilla's: {@link #SKY_OVERWORLD},
     * {@link #SKY_END}, or {@link #SKY_NONE} for vanilla's own.
     *
     * @param camera      the kind of sky the camera's level has
     * @param destination the kind of sky the destination has
     */
    int skyKind(DimensionType.Skybox camera, DimensionType.Skybox destination);

    /**
     * The shade mode of a view: {@link #SHADE_LIT_SHADOWED} when the renderer's shadow map covers the destination,
     * {@link #SHADE_LIT} when it can light the view but not shadow it, {@link #SHADE_LIT_FOREIGN} when it lights the view
     * with another dimension's light than the camera's, {@link #SHADE_VANILLA} otherwise.
     *
     * @param sameLevel   whether the destination is the level the camera is in, unscaled and unreflected
     * @param camera      the kind of sky the camera's level has, whose light the renderer's frame describes
     * @param destination the kind of sky the destination has
     * @param depth       the view's nesting depth, 0 for a view seen directly
     */
    int shadeMode(boolean sameLevel, DimensionType.Skybox camera, DimensionType.Skybox destination, int depth);

    /**
     * Writes the renderer's cloud parameters, re-based on a view's camera in the destination world, as
     * {@link #CLOUD_BYTES} bytes at the buffer's position; zeros when the renderer has none this frame.
     */
    void writeClouds(ByteBuffer out, double cameraX, double cameraY, double cameraZ);

    /** Binds what the renderer's sky stage samples beyond the view's Portal block: its cloud weather map and noise volume. */
    void bindSky(RenderPass pass);

    /** Whether the renderer shades the water of a view with its own water model, which needs {@link #captureScene()} before the translucent pass. */
    boolean shadesWater();

    /**
     * Writes the renderer's per-frame state, re-based on a view's camera, as {@link #FRAME_BYTES} bytes at the buffer's
     * position: the view rotation and projection the view draws with, its camera in the destination world, and the size
     * of its target; a destination under another kind of sky than the camera's gets that sky's light where the renderer
     * knows it. A renderer without such state writes zeros.
     */
    void writeFrame(ByteBuffer out, Matrix4fc view, Matrix4fc projection, double cameraX, double cameraY, double cameraZ, int width, int height,
                    DimensionType.Skybox destination);

    /** Copies the open render pass's colour and depth for the renderer's refraction and reflections; render thread, inside a pass. */
    void captureScene();
}
