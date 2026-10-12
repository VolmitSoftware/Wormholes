package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.stream.EnvironmentState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record IrisMeshCamera(List<ApertureDescriptor> traversal, String source, String destination, Similarity transform) {
    public IrisMeshCamera {
        List<ApertureDescriptor> normalized = new ArrayList<>(traversal.size());
        for (ApertureDescriptor aperture : traversal) {
            normalized.add(geometry(aperture));
        }
        traversal = List.copyOf(normalized);
        if (traversal.isEmpty()) {
            throw new IllegalArgumentException("A streamed camera needs an aperture traversal");
        }
        Objects.requireNonNull(source);
        Objects.requireNonNull(destination);
        Objects.requireNonNull(transform);
    }

    public static IrisMeshCamera of(List<ApertureDescriptor> traversal, String source, EnvironmentState environment) {
        return new IrisMeshCamera(traversal, source, environment.world().dimensionKey(), Similarity.of(environment.transform(), environment.scale()));
    }

    IrisViewPipelinePath append(List<IrisViewPipelinePath.Camera> enclosing, String dimension) {
        List<IrisViewPipelinePath.Camera> cameras = new ArrayList<>(enclosing.size() + 1);
        cameras.addAll(enclosing);
        cameras.add(new IrisViewPipelinePath.Camera("STREAMED", this, source, destination, transform));
        return new IrisViewPipelinePath(cameras, dimension);
    }

    private static ApertureDescriptor geometry(ApertureDescriptor geometry) {
        return new ApertureDescriptor(geometry.originX(), geometry.originY(), geometry.originZ(), geometry.facing(), geometry.frontSide(),
            geometry.quarterTurns(), geometry.mirror(), geometry.apertureWidth(), geometry.apertureHeight(), geometry.apertureMask(),
            geometry.shape(), 0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0, 0, 0, 0, geometry.planeOffset(), 0, geometry.targetIdentity(), List.of());
    }
}
