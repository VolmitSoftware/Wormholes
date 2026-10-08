package art.arcane.wormholes.modded.client.world;

import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

public class PreparedLevelExtractorTest extends MinecraftTestBase {
    @Test
    public void stagedLifecycleAndDirtyCallbacksNeverReachTheMainRenderer() throws ReflectiveOperationException {
        Minecraft minecraft = mock(Minecraft.class);
        LevelRenderer renderer = mock(LevelRenderer.class);
        Field rendererField = Minecraft.class.getDeclaredField("levelRenderer");
        rendererField.setAccessible(true);
        rendererField.set(minecraft, renderer);
        Field debugField = Minecraft.class.getDeclaredField("debugEntries");
        debugField.setAccessible(true);
        debugField.set(minecraft, mock(DebugScreenEntryList.class));
        ClientLevel staged = mock(ClientLevel.class);
        try (MockedStatic<Minecraft> access = mockStatic(Minecraft.class)) {
            access.when(Minecraft::getInstance).thenReturn(minecraft);
            LevelExtractor extractor = new PreparedLevelExtractor(minecraft);
            extractor.setLevel(staged);
            extractor.allChanged();
            extractor.blockChanged(BlockPos.ZERO, 3);
            extractor.setBlockDirty(BlockPos.ZERO, null, null);
            extractor.setBlocksDirty(0, 0, 0, 32, 32, 32);
            extractor.setSectionDirtyWithNeighbors(0, 0, 0);
            extractor.setSectionRangeDirty(0, 0, 0, 2, 2, 2);
            extractor.setSectionDirty(0, 0, 0);
            extractor.setLevel(null);
            verifyNoInteractions(minecraft, renderer, staged);
        }
    }
}
