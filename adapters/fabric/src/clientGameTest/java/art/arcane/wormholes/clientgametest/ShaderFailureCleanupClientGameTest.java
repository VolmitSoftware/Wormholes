package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.render.iris.IrisShaderStages;
import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.irisshaders.iris.gl.program.ProgramBuilder;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import net.irisshaders.iris.pipeline.programs.PartialShader;
import net.irisshaders.iris.pipeline.programs.ShaderCreator;
import org.lwjgl.opengl.GL20C;

import java.util.List;

public final class ShaderFailureCleanupClientGameTest implements FabricClientGameTest {
    private static final String VERTEX = "#version 330 core\nvoid main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }";
    private static final String FRAGMENT = "#version 330 core\nout vec4 color; void main() { color = vec4(1.0); }";
    private static final String BROKEN = "#version 330 core\nvoid main() { this is invalid GLSL; }";
    private static final String LINK_VERTEX = "#version 330 core\nout vec3 wormholesTest_varying;"
        + " void main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); wormholesTest_varying = vec3(1.0); }";
    private static final String LINK_FRAGMENT = "#version 330 core\nin vec4 wormholesTest_varying;"
        + " out vec4 color; void main() { color = wormholesTest_varying; }";

    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            IrisShaderStages.clear();
            compileFailure(true);
            compileFailure(false);
            List<Integer> reusable = linkFailure(true);
            linkFailure(false);
            for (int shader : reusable) {
                assertTrue(GL20C.glIsShader(shader), "failed link retry deleted a reusable compiled stage");
            }
            recover();
            IrisShaderStages.clear();
            assertTrue(IrisShaderStages.diagnostics().leases() == 0 && IrisShaderStages.diagnostics().cached() == 0,
                "shader failure cleanup left cache ownership behind");
            System.out.println("Shader failure cleanup accepted both compilation paths, both failed-link paths, and recovery");
        });
    }

    private static void compileFailure(boolean creator) {
        ShaderAllocationTap.start();
        ShaderCompileException failure = null;
        ShaderAllocationTap.Snapshot allocations;
        try {
            if (creator) {
                ShaderCreator.link("wormholes_test_compile", VERTEX, null, null, null, BROKEN, DefaultVertexFormat.POSITION, false);
            } else {
                ProgramBuilder.begin("wormholes_test_compile", VERTEX, null, BROKEN, ImmutableSet.of());
            }
        } catch (ShaderCompileException rejected) {
            failure = rejected;
        } finally {
            allocations = ShaderAllocationTap.stop();
        }
        assertTrue(failure != null, "invalid fragment unexpectedly compiled");
        assertTrue(!allocations.shaders().isEmpty(), "native allocation tap did not observe the failed stage");
        assertTrue(!GL20C.glIsShader(allocations.shaders().getLast()), "failed shader allocation is still live");
        for (int program : allocations.programs()) {
            assertTrue(!GL20C.glIsProgram(program), "compile failure leaked program " + program);
        }
        assertTrue(IrisShaderStages.diagnostics().leases() == 0, "compile failure leaked stage leases");
        assertTrue(IrisShaderStages.diagnostics().cached() > 0, "compile failure discarded a successful cached stage");
    }

    private static List<Integer> linkFailure(boolean creator) {
        ShaderAllocationTap.start();
        ShaderCompileException failure = null;
        ShaderAllocationTap.Snapshot allocations;
        try {
            if (creator) {
                ShaderCreator.link("wormholes_test_link", LINK_VERTEX, null, null, null, LINK_FRAGMENT, DefaultVertexFormat.POSITION, false);
            } else {
                ProgramBuilder.begin("wormholes_test_link", LINK_VERTEX, null, LINK_FRAGMENT, ImmutableSet.of());
            }
        } catch (ShaderCompileException rejected) {
            failure = rejected;
        } finally {
            allocations = ShaderAllocationTap.stop();
        }
        assertTrue(failure != null, "mismatched stage interfaces unexpectedly linked");
        assertTrue(allocations.shaders().size() == (creator ? 2 : 0) && allocations.programs().size() == 1,
            "native taps did not observe compiled-stage reuse and the failed program");
        assertTrue(!GL20C.glIsProgram(allocations.programs().getFirst()), "failed link program is still live");
        for (int shader : allocations.shaders()) {
            assertTrue(GL20C.glIsShader(shader), "failed link deleted a reusable compiled stage");
        }
        assertTrue(IrisShaderStages.diagnostics().leases() == 0, "failed link leaked stage leases");
        return allocations.shaders();
    }

    private static void recover() {
        PartialShader shader = ShaderCreator.link("wormholes_test_recovery", VERTEX, null, null, null, FRAGMENT,
            DefaultVertexFormat.POSITION, false);
        try {
            assertTrue(GL20C.glGetProgrami(shader.program(), GL20C.GL_LINK_STATUS) == GL20C.GL_TRUE,
                "ShaderCreator did not recover after compilation failure");
            assertTrue(GL20C.glGetProgrami(shader.program(), GL20C.GL_ATTACHED_SHADERS) == 0,
                "successful ShaderCreator link did not detach cached stages");
        } finally {
            GL20C.glDeleteProgram(shader.program());
        }
        ProgramBuilder.begin("wormholes_test_recovery", VERTEX, null, FRAGMENT, ImmutableSet.of()).build().destroy();
        assertTrue(IrisShaderStages.diagnostics().leases() == 0, "successful recovery leaked stage leases");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
