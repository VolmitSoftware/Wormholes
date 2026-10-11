package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.render.iris.IrisClipPlanes;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class NativeSodiumTerrainProbe {
    private static final Map<String, Integer> COUNTS = new HashMap<>();
    private static int probes;

    private NativeSodiumTerrainProbe() {
    }

    public static Frame begin(ChunkRenderMatrices matrices, ChunkRenderListIterable lists, TerrainRenderPass pass) {
        PortalStencilRenderer renderer = PortalStencilRenderer.instance();
        Camera camera = renderer.camera();
        Minecraft minecraft = Minecraft.getInstance();
        if (!renderer.nested() || camera == null || minecraft.level == null || probes >= 24) {
            return null;
        }
        Vec3 position = camera.position();
        String key = minecraft.level.dimension().identifier() + ":" + BlockPos.containing(position) + ":"
            + pass.isTranslucent() + ":" + pass.supportsFragmentDiscard();
        int ordinal = COUNTS.merge(key, 1, Integer::sum);
        if (ordinal != 1 && ordinal != 30 && ordinal != 120) {
            return null;
        }
        int regions = 0;
        int sections = 0;
        Iterator<ChunkRenderList> iterator = lists.iterator(false);
        while (iterator.hasNext()) {
            ChunkRenderList list = iterator.next();
            regions++;
            sections += list.getSectionsWithGeometryCount();
        }
        probes++;
        Frame frame = new Frame(new Descriptor(key, ordinal, regions, sections, matrices.projection().toString(), matrices.modelView().toString(), position));
        System.out.println("WORMHOLES_SODIUM_TERRAIN_BEGIN " + frame);
        return frame;
    }

    public static int beginDraw(Frame frame) {
        if (frame == null || frame.draws != 0) {
            return 0;
        }
        if (GL15C.glGetQueryi(GL15C.GL_SAMPLES_PASSED, GL15C.GL_CURRENT_QUERY) != 0) {
            throw new AssertionError("Sodium terrain probe encountered an active GPU samples query");
        }
        int query = GL15C.glGenQueries();
        GL15C.glBeginQuery(GL15C.GL_SAMPLES_PASSED, query);
        return query;
    }

    public static void endDraw(Frame frame, int query, int commands) {
        if (frame == null) {
            return;
        }
        frame.draws++;
        frame.commands += commands;
        if (query == 0) {
            return;
        }
        GL15C.glEndQuery(GL15C.GL_SAMPLES_PASSED);
        try {
            long samples = Integer.toUnsignedLong(GL15C.glGetQueryObjectui(query, GL15C.GL_QUERY_RESULT));
            System.out.println("WORMHOLES_SODIUM_TERRAIN_DRAW key=" + frame.descriptor.key() + " ordinal=" + frame.descriptor.ordinal() + " commands=" + commands
                + " samples=" + samples + " clipping=" + IrisClipPlanes.capture() + " stencil=" + GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST)
                + " stencilFunction=" + GL11C.glGetInteger(GL11C.GL_STENCIL_FUNC) + " stencilReference=" + GL11C.glGetInteger(GL11C.GL_STENCIL_REF)
                + " framebuffer=" + GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING)
                + " depth=" + GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST) + " depthFunction=" + GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC)
                + " frontFace=" + GL11C.glGetInteger(GL11C.GL_FRONT_FACE) + " culling=" + GL11C.glIsEnabled(GL11C.GL_CULL_FACE)
                + " program=" + GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM));
        } finally {
            GL15C.glDeleteQueries(query);
        }
    }

    public static void end(Frame frame) {
        if (frame == null) {
            return;
        }
        System.out.println("WORMHOLES_SODIUM_TERRAIN_END key=" + frame.descriptor.key() + " ordinal=" + frame.descriptor.ordinal()
            + " draws=" + frame.draws + " commands=" + frame.commands);
        if (frame.descriptor.ordinal() == 30 && frame.descriptor.key().startsWith("minecraft:the_end:") && frame.descriptor.camera().distanceTo(new Vec3(1.5D, 71.62D, 226.5D)) < 0.05D
            && frame.descriptor.key().endsWith(":false:false") && NativeSodiumTerrainProbe.class.getClassLoader().getResource(
                "com/seibel/distanthorizons/core/api/internal/ClientApi.class") != null) {
            DistantHorizonsTap.terrainImage();
        }
    }

    public static final class Frame {
        private final Descriptor descriptor;
        private int draws;
        private int commands;

        private Frame(Descriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public String toString() {
            return descriptor.toString();
        }
    }

    private record Descriptor(String key, int ordinal, int regions, int sections, String projection, String modelView, Vec3 camera) {
    }
}
