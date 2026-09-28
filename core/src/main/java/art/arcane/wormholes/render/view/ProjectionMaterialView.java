package art.arcane.wormholes.render.view;

public interface ProjectionMaterialView<B, M> extends ProjectionBlockView<B> {
    M sampleMaterial(int x, int y, int z);
}
