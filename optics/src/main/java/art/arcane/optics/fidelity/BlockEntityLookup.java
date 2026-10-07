package art.arcane.optics.fidelity;

@FunctionalInterface
public interface BlockEntityLookup {
    BlockEntitySample sample(int x, int y, int z);
}
