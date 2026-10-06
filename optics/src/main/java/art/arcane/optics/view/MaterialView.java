package art.arcane.optics.view;

public interface MaterialView<B, M> extends BlockView<B> {
    M sampleMaterial(int x, int y, int z);

    default int buriedDepth(int x, int y, int z) {
        return -1;
    }
}
