package art.arcane.optics.internal.occlusion;

@FunctionalInterface
public interface LocalOccupancy {
    ProjectorHoldProof.Occupancy occupancy(int x, int y, int z);
}
