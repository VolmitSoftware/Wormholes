package art.arcane.wormholes.render.view;

public interface ProjectionMaterialView<B, M> extends ProjectionBlockView<B> {
    M sampleMaterial(int x, int y, int z);

    default int buriedDepth(int x, int y, int z) {
        return -1;
    }
}
