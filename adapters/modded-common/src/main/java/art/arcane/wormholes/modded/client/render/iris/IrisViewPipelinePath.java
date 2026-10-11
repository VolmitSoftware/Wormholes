package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.optics.frame.Similarity;

import java.util.List;
import java.util.Objects;

record IrisViewPipelinePath(List<Camera> cameras, String dimension) {
    IrisViewPipelinePath {
        cameras = List.copyOf(cameras);
        if (cameras.isEmpty()) {
            throw new IllegalArgumentException("A portal pipeline needs a camera traversal");
        }
        Objects.requireNonNull(dimension);
    }

    record Camera(String kind, Object key, String source, String destination, Similarity transform) {
        Camera {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(key);
            Objects.requireNonNull(source);
            Objects.requireNonNull(destination);
            Objects.requireNonNull(transform);
        }
    }
}
