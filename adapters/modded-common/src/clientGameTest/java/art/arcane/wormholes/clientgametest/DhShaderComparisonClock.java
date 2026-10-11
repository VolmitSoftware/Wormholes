package art.arcane.wormholes.clientgametest;

import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.lwjgl.opengl.GL20C;

public final class DhShaderComparisonClock {
    private static float phase = Float.NaN;
    private static int mainUploads;
    private static int portalUploads;

    private DhShaderComparisonClock() {
    }

    public static void capture() {
        if (!Float.isNaN(phase)) {
            throw new IllegalStateException("DH shader comparison clock is already captured");
        }
        float captured = SystemTimeUniforms.TIMER.getFrameTimeCounter();
        if (!Float.isFinite(captured)) {
            throw new AssertionError("DH comparison received a non-finite shader phase");
        }
        mainUploads = 0;
        portalUploads = 0;
        phase = captured;
        System.out.println("WORMHOLES_DH_SHADER_COMPARISON_PHASE " + phase);
    }

    public static float phase() {
        return phase;
    }

    public static float release() {
        phase = Float.NaN;
        return SystemTimeUniforms.TIMER.getFrameTimeCounter();
    }

    public static void uploaded(int program) {
        Minecraft minecraft = Minecraft.getInstance();
        if (Float.isNaN(phase) || minecraft.level == null || !minecraft.level.dimension().equals(Level.END)) {
            return;
        }
        int location = GL20C.glGetUniformLocation(program, "frameTimeCounter");
        if (location < 0) {
            return;
        }
        float actual = GL20C.glGetUniformf(program, location);
        if (Math.abs(actual - phase) > 0.0001F) {
            throw new AssertionError("Actual End shader program has a different comparison phase: program=" + program
                + " expected=" + phase + " actual=" + actual);
        }
        if (minecraft.player.level() == minecraft.level) {
            mainUploads++;
        } else {
            portalUploads++;
        }
    }

    public static void verify(boolean portal) {
        if (Float.isNaN(phase)) {
            return;
        }
        int uploads = portal ? portalUploads : mainUploads;
        if (uploads == 0) {
            throw new AssertionError("DH comparison has no actual shader phase upload proof: portal=" + portal);
        }
        System.out.println("WORMHOLES_DH_SHADER_PHASE_UPLOAD_PASS portal=" + portal + " phase=" + phase + " uploads=" + uploads);
    }

    public static boolean advancing(float previous) {
        return Float.isNaN(phase) && SystemTimeUniforms.TIMER.getFrameTimeCounter() > previous;
    }
}
