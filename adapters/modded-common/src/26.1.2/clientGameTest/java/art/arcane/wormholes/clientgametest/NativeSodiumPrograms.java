package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.mixin.client.IrisPortalSodiumProgramsAccess;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.SodiumPrograms;
import org.lwjgl.opengl.GL20C;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NativeSodiumPrograms {
    private static final List<String> ATTRIBUTES = List.of("a_Position", "a_Color", "a_TexCoord", "a_LightAndData", "iris_Normal",
        "mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock");

    private NativeSodiumPrograms() {
    }

    public static List<Integer> owned(IrisRenderingPipeline main, List<IrisRenderingPipeline> views, String phase) {
        EnumMap<SodiumPrograms.Pass, GlProgram<ChunkShaderInterface>> reference = shaders(main);
        assertTrue(!reference.isEmpty(), phase + ": the main pipeline has no Sodium terrain programs");
        Set<Integer> live = new HashSet<>();
        for (GlProgram<ChunkShaderInterface> program : reference.values()) {
            live.add(program.handle());
        }
        List<Integer> owned = new ArrayList<>(views.size() * reference.size());
        for (IrisRenderingPipeline view : views) {
            EnumMap<SodiumPrograms.Pass, GlProgram<ChunkShaderInterface>> programs = shaders(view);
            assertTrue(programs.keySet().equals(reference.keySet()), phase + ": a portal pipeline has Sodium terrain passes " + programs.keySet()
                + " instead of " + reference.keySet());
            for (Map.Entry<SodiumPrograms.Pass, GlProgram<ChunkShaderInterface>> entry : programs.entrySet()) {
                int handle = entry.getValue().handle();
                assertTrue(handle > 0 && GL20C.glIsProgram(handle), phase + ": Sodium terrain program " + handle + " for " + entry.getKey()
                    + " is not a live GL program");
                assertTrue(live.add(handle), phase + ": Sodium terrain program " + handle + " for " + entry.getKey()
                    + " is shared between live pipelines");
                int expected = reference.get(entry.getKey()).handle();
                for (String attribute : ATTRIBUTES) {
                    int location = GL20C.glGetAttribLocation(handle, attribute);
                    int mainLocation = GL20C.glGetAttribLocation(expected, attribute);
                    assertTrue(location == mainLocation, phase + ": " + entry.getKey() + " binds " + attribute + " to " + location
                        + " instead of the main pipeline's " + mainLocation);
                }
                owned.add(handle);
            }
        }
        System.out.println("Iris Sodium terrain programs phase=" + phase + " passes=" + reference.size() + " pipelines=" + views.size()
            + " programs=" + owned);
        return owned;
    }

    public static void released(List<IrisRenderingPipeline> destroyed, List<Integer> programs, String phase) {
        assertTrue(!programs.isEmpty(), phase + ": no Sodium terrain programs were captured before destruction");
        for (IrisRenderingPipeline pipeline : destroyed) {
            int kept = shaders(pipeline).size();
            assertTrue(kept == 0, phase + ": a destroyed pipeline kept " + kept + " Sodium terrain programs");
        }
        for (int program : programs) {
            assertTrue(!GL20C.glIsProgram(program), phase + ": a destroyed pipeline left Sodium terrain program " + program + " allocated");
        }
        System.out.println("Iris Sodium terrain programs phase=" + phase + " released=" + programs);
    }

    private static EnumMap<SodiumPrograms.Pass, GlProgram<ChunkShaderInterface>> shaders(IrisRenderingPipeline pipeline) {
        return ((IrisPortalSodiumProgramsAccess) pipeline.getSodiumPrograms()).wormholes$shaders();
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
