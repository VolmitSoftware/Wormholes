package art.arcane.optics.client;

@FunctionalInterface
public interface LightSampler {
    int light(int x, int y, int z);
}
