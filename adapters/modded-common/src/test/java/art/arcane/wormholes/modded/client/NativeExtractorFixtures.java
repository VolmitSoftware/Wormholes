package art.arcane.wormholes.modded.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

final class NativeExtractorFixtures {
    static final String FIELD = "levelExtractor";

    private NativeExtractorFixtures() {
    }

    static Object extractor() {
        return mock(LevelExtractor.class);
    }

    static void untouched(Minecraft minecraft) {
        verify(minecraft.levelExtractor, never()).setLevel(any());
    }
}
