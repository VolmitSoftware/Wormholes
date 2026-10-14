package art.arcane.wormholes.modded.client.world;

import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.WorldBorderRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class PreparedLevelExtractorTest extends MinecraftTestBase {
    @Test
    public void stagedLifecycleAndDirtyCallbacksNeverReachTheMainRenderer() throws ReflectiveOperationException {
        Minecraft minecraft = mock(Minecraft.class);
        LevelRenderer renderer = mock(LevelRenderer.class);
        GameRenderer gameRenderer = mock(GameRenderer.class);
        when(gameRenderer.getFeatureRenderDispatcher()).thenReturn(mock(FeatureRenderDispatcher.class));
        field(minecraft, "levelRenderer", renderer);
        field(minecraft, "gameRenderer", gameRenderer);
        field(minecraft, "debugEntries", mock(DebugScreenEntryList.class));
        ClientLevel staged = mock(ClientLevel.class);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class);
             MockedConstruction<CloudRenderer> clouds = mockConstruction(CloudRenderer.class);
             MockedConstruction<WorldBorderRenderer> borders = mockConstruction(WorldBorderRenderer.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            LevelRenderer extractor = new PreparedLevelExtractor(minecraft);
            clearInvocations(minecraft, gameRenderer);
            extractor.setLevel(staged);
            extractor.allChanged();
            extractor.blockChanged(staged, BlockPos.ZERO, null, null, 3);
            extractor.setBlockDirty(BlockPos.ZERO, null, null);
            extractor.setBlocksDirty(0, 0, 0, 32, 32, 32);
            extractor.setSectionDirtyWithNeighbors(0, 0, 0);
            extractor.setSectionRangeDirty(0, 0, 0, 2, 2, 2);
            extractor.setSectionDirty(0, 0, 0);
            extractor.onSectionBecomingNonEmpty(0L);
            extractor.destroyBlockProgress(1, BlockPos.ZERO, 3);
            extractor.setLevel(null);
            verifyNoInteractions(minecraft, renderer, staged);
        }
    }

    private static void field(Minecraft minecraft, String name, Object value) throws ReflectiveOperationException {
        Field field = Minecraft.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(minecraft, value);
    }
}
