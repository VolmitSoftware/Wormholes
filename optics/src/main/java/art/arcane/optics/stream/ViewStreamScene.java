package art.arcane.optics.stream;

import java.util.UUID;

public interface ViewStreamScene<P> {
    default long effectCapability() {
        return ViewStreamCapability.NONE;
    }

    ViewStreamMessage.Extension effects(P observer, UUID endpoint, int key, long tick, boolean full);

    ViewStreamMessage.Atmosphere atmosphere(P observer, UUID endpoint, int key, long tick, boolean full);

    default ViewStreamMessage.Environment environment(P observer, UUID endpoint, int key, long tick, boolean full) {
        return null;
    }

    default ViewStreamMessage.Environment nestedEnvironment(P observer, UUID parent, UUID endpoint, int key, long tick, boolean full) {
        return null;
    }

    default boolean environmentUnavailable(P observer, UUID parent, UUID endpoint) {
        return false;
    }

    static <P> ViewStreamScene<P> none() {
        return new ViewStreamScene<P>() {
            @Override
            public ViewStreamMessage.Extension effects(P observer, UUID endpoint, int key, long tick, boolean full) {
                return null;
            }

            @Override
            public ViewStreamMessage.Atmosphere atmosphere(P observer, UUID endpoint, int key, long tick, boolean full) {
                return null;
            }
        };
    }
}
