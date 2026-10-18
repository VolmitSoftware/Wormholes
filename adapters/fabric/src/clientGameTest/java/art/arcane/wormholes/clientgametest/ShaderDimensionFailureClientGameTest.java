package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.render.iris.IrisPipelines;
import art.arcane.wormholes.modded.mixin.client.IrisPortalPipelineAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalStorageBuffersAccess;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.buffer.BuiltShaderStorageInfo;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.world.level.GameType;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15C;

import java.util.List;
import java.util.Map;

public final class ShaderDimensionFailureClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enable();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            FabricConnection.of(singleplayer).waitForChunksDownload();
            singleplayer.getServer().runOnServer(server -> FabricConnection.of(singleplayer).getServerPlayer().setGameMode(GameType.CREATIVE));
            WorldRenderingPipeline selected = context.computeOnClient(client -> verify());
            int started = context.computeOnClient(client -> SystemTimeUniforms.COUNTER.getAsInt());
            context.waitFor(client -> {
                require(client.player != null && client.player.isAlive() && ClientGui.screen(client) == null,
                    "Shader observation requires a living player with no open GUI");
                require(NativeTestWindow.hiddenAndUnfocused(client) && NativeTestWindow.mouseReleased(client),
                    "Shader observation requires a hidden, unfocused native client with released mouse");
                require(Iris.isPackInUseQuick() && Iris.getPipelineManager().getPipelineNullable() == selected,
                    "Optional dimension failure changed the active shader pipeline");
                return SystemTimeUniforms.COUNTER.getAsInt() - started >= 100;
            }, 600);
            context.takeScreenshot("shader-optional-dimension-failure-overworld");
            System.out.println("WORMHOLES_IRIS_DIMENSION_FAILURE_PASS source=true rejectedNether=true rejectedEnd=true frames=100");
        }
    }

    private static WorldRenderingPipeline verify() {
        WorldRenderingPipeline selected = Iris.getPipelineManager().getPipelineNullable();
        require(Iris.isPackInUseQuick() && selected instanceof IrisRenderingPipeline, "A valid Overworld shader pipeline is required");
        boolean storageSupported = GL.getCapabilities().OpenGL43 || GL.getCapabilities().GL_ARB_shader_storage_buffer_object;
        ShaderStorageBufferHolder holder = null;
        if (storageSupported) {
            Int2ObjectArrayMap<BuiltShaderStorageInfo> buffers = new Int2ObjectArrayMap<>();
            buffers.put(6, new BuiltShaderStorageInfo(1024L, false, 1F, 1F, new byte[1024]));
            holder = new ShaderStorageBufferHolder(buffers, 1920, 1080);
            holder.setupBuffers();
        }
        try {
            List<ShaderStorageBuffer> retained = List.copyOf(IrisPortalStorageBuffersAccess.wormholes$activeBuffers());
            ShaderAllocationTap.start();
            ShaderAllocationTap.Snapshot allocations;
            try {
                IrisPipelines.createAll();
            } finally {
                allocations = ShaderAllocationTap.stop();
            }
            Map<NamespacedId, WorldRenderingPipeline> dimensions = ((IrisPortalPipelineAccess) Iris.getPipelineManager()).wormholes$pipelines();
            require(!allocations.shaders().isEmpty(), "The invalid destination shaders were not compiled");
            require(dimensions.get(DimensionId.NETHER) == null && dimensions.get(DimensionId.END) == null,
                "A failed optional dimension was cached as a ready pipeline");
            require(Iris.getPipelineManager().getPipelineNullable() == selected && Iris.isPackInUseQuick(),
                "Destination compilation disabled the active root shader");
            for (ShaderStorageBuffer buffer : retained) {
                require(IrisPortalStorageBuffersAccess.wormholes$activeBuffers().contains(buffer) && GL15C.glIsBuffer(buffer.getId()),
                    "Destination compilation deleted a retained shader storage buffer");
            }
            System.out.println("WORMHOLES_IRIS_DIMENSION_FAILURE_STORAGE supported=" + storageSupported + " retained=" + retained.size());
            return selected;
        } finally {
            if (holder != null) {
                holder.destroyBuffers();
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
