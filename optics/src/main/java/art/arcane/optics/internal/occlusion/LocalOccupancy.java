package art.arcane.optics.internal.occlusion;

@FunctionalInterface
public interface LocalOccupancy {
    HoldProof.Occupancy occupancy(int x, int y, int z);
}
