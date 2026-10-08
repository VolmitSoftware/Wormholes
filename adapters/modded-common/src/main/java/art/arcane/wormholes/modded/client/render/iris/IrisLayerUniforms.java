package art.arcane.wormholes.modded.client.render.iris;

public final class IrisLayerUniforms {
    private static final int TRANSITION_BITS = 11;
    private static final int TRANSITION_MASK = (1 << TRANSITION_BITS) - 1;
    private static int counter = -1;
    private static int transitions;

    private IrisLayerUniforms() {
    }

    public static int frame(int frameCounter) {
        if (frameCounter != counter) {
            counter = frameCounter;
            transitions = 0;
        }
        return frameCounter << TRANSITION_BITS | transitions & TRANSITION_MASK;
    }

    public static void transition() {
        transitions++;
    }
}
