package art.arcane.optics.stream;

import java.util.UUID;

public interface ViewStreamScene<O> {
    default long effectCapability() {
        return ViewStreamCapability.NONE;
    }

    ViewStreamMessage.Extension effects(O observer, UUID endpoint, int key, long tick, boolean full);

    ViewStreamMessage.Atmosphere atmosphere(O observer, UUID endpoint, int key, long tick, boolean full);

    default ViewStreamMessage.Environment environment(O observer, UUID endpoint, int key, long tick, boolean full) {
        return null;
    }

    default ViewStreamMessage.Environment nestedEnvironment(O observer, UUID parent, UUID endpoint, int key, long tick, boolean full) {
        return null;
    }

    default boolean environmentUnavailable(O observer, UUID parent, UUID endpoint) {
        return false;
    }

    static <O> ViewStreamScene<O> none() {
        return new ViewStreamScene<O>() {
            @Override
            public ViewStreamMessage.Extension effects(O observer, UUID endpoint, int key, long tick, boolean full) {
                return null;
            }

            @Override
            public ViewStreamMessage.Atmosphere atmosphere(O observer, UUID endpoint, int key, long tick, boolean full) {
                return null;
            }
        };
    }
}
