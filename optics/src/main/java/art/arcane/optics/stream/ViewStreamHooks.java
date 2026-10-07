package art.arcane.optics.stream;

import java.util.function.Predicate;

public interface ViewStreamHooks<O> {
    boolean onExtension(O peer, Object payload);

    void tickExtension(O peer, long nowMillis, Predicate<ViewStreamMessage> sender);

    void onReset(O peer);

    void onClose(O peer);

    static <O> ViewStreamHooks<O> none() {
        return new ViewStreamHooks<O>() {
            @Override
            public boolean onExtension(O peer, Object payload) {
                return false;
            }

            @Override
            public void tickExtension(O peer, long nowMillis, Predicate<ViewStreamMessage> sender) {
            }

            @Override
            public void onReset(O peer) {
            }

            @Override
            public void onClose(O peer) {
            }
        };
    }
}
