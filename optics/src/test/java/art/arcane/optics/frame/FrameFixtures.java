package art.arcane.optics.frame;

import java.util.ArrayList;
import java.util.List;

import art.arcane.optics.math.Face;

final class FrameFixtures {
    private FrameFixtures() {
    }

    static List<Frame> all() {
        List<Frame> frames = new ArrayList<Frame>(24);
        for (Face normal : Face.values()) {
            for (Face up : Face.values()) {
                if (normal.getAxis() != up.getAxis()) {
                    frames.add(Frame.fromNormalUp(normal, up));
                }
            }
        }
        return frames;
    }
}
