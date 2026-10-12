package art.arcane.wormholes.modded.client.render.iris;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class IrisCameraAdmission {
    private IrisCameraAdmission() {
    }

    static <K> Set<K> plan(int capacity, List<Set<K>> priorities) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Camera capacity must be positive");
        }
        Set<K> admitted = new LinkedHashSet<>();
        for (Set<K> priority : priorities) {
            for (K camera : priority) {
                admitted.add(camera);
                if (admitted.size() == capacity) {
                    return Collections.unmodifiableSet(admitted);
                }
            }
        }
        return Collections.unmodifiableSet(admitted);
    }
}
