package art.arcane.wormholes.clientgametest;

import java.util.ArrayList;
import java.util.List;

public final class ShaderAllocationTap {
    private static final List<Integer> SHADERS = new ArrayList<>();
    private static final List<Integer> PROGRAMS = new ArrayList<>();
    private static boolean active;

    private ShaderAllocationTap() {
    }

    public static void start() {
        SHADERS.clear();
        PROGRAMS.clear();
        active = true;
    }

    public static Snapshot stop() {
        active = false;
        return new Snapshot(List.copyOf(SHADERS), List.copyOf(PROGRAMS));
    }

    public static void shader(int shader) {
        if (active && shader > 0) {
            SHADERS.add(shader);
        }
    }

    public static void program(int program) {
        if (active && program > 0) {
            PROGRAMS.add(program);
        }
    }

    public record Snapshot(List<Integer> shaders, List<Integer> programs) {
    }
}
