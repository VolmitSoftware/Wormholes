package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.render.PortalFramebufferScope;
import art.arcane.wormholes.modded.client.render.PortalTextureScope;
import art.arcane.wormholes.modded.client.render.iris.IrisPipelines;
import art.arcane.wormholes.modded.mixin.client.IrisPortalPipelineAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalSettingsAccess;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;

import java.util.Map;

public final class ShaderPreloadStateClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enable();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            FabricConnection.of(singleplayer).waitForChunksDownload();
            context.runOnClient(client -> verify());
            context.waitTicks(20);
            context.runOnClient(client -> assertTrue(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline,
                "Dimension preparation did not leave the main shader pipeline usable"));
        }
    }

    private static void verify() {
        PipelineManager manager = Iris.getPipelineManager();
        WorldRenderingPipeline selected = manager.getPipelineNullable();
        assertTrue(Iris.isPackInUseQuick() && selected instanceof IrisRenderingPipeline, "Shader preload acceptance requires an active Iris pack");
        Map<NamespacedId, WorldRenderingPipeline> pipelines = ((IrisPortalPipelineAccess) manager).wormholes$pipelines();
        WorldRenderingPipeline previous = pipelines.remove(DimensionId.NETHER);
        assertTrue(previous instanceof IrisRenderingPipeline && previous != selected, "The destination pipeline was not prepared independently");
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        float ambientOcclusion = settings.getAmbientOcclusionLevel();
        boolean reload = settings.isReloadRequired();
        int texture = GL11C.glGenTextures();
        int draw = GL30C.glGenFramebuffers();
        int read = GL30C.glGenFramebuffers();
        try (PortalTextureScope textures = new PortalTextureScope();
             PortalFramebufferScope framebuffer = PortalFramebufferScope.capture()) {
            previous.destroy();
            settings.setAmbientOcclusionLevel(0.375F);
            ((IrisPortalSettingsAccess) settings).wormholes$reloadRequired(true);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE7);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, 17, 19, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, 0L);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, draw);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, texture, 0);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, read);
            GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, texture, 0);
            GL11C.glViewport(3, 5, 17, 19);
            assertTrue(!IrisPipelines.loadingStep(), "Dimension preparation did not recreate the missing destination pipeline");
            assertTrue(pipelines.get(DimensionId.NETHER) instanceof IrisRenderingPipeline
                && pipelines.get(DimensionId.NETHER) != previous, "Dimension preparation reused the destroyed pipeline");
            assertTrue(manager.getPipelineNullable() == selected, "Dimension preparation replaced the main pipeline selection");
            assertTrue(settings.getAmbientOcclusionLevel() == 0.375F && settings.isReloadRequired(),
                "Destination preparation overwrote the active world settings");
            assertTrue(GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE) == GL13C.GL_TEXTURE7
                && GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D) == texture,
                "Destination preparation overwrote the active texture bindings");
            assertTrue(GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING) == draw
                && GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING) == read,
                "Destination preparation overwrote the active framebuffer bindings");
            int[] viewport = new int[4];
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
            assertTrue(viewport[0] == 3 && viewport[1] == 5 && viewport[2] == 17 && viewport[3] == 19,
                "Destination preparation overwrote the active viewport");
            System.out.println("WORMHOLES_IRIS_PRELOAD_STATE_PASS selected=true settings=true textures=true framebuffers=true viewport=true");
        } finally {
            settings.setAmbientOcclusionLevel(ambientOcclusion);
            ((IrisPortalSettingsAccess) settings).wormholes$reloadRequired(reload);
            GL11C.glDeleteTextures(texture);
            GL30C.glDeleteFramebuffers(draw);
            GL30C.glDeleteFramebuffers(read);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
