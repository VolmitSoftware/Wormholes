package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientArrivalLoadingTest extends MinecraftTestBase {
    @Test
    public void preparedPlayerSectionCompletesVanillaTrackerWithoutClosingOtherScreens() throws Exception {
        for (boolean loadingScreen : new boolean[]{true, false}) {
            Minecraft minecraft = mock(Minecraft.class);
            ClientLevel level = mock(ClientLevel.class);
            LocalPlayer player = mock(LocalPlayer.class);
            when(player.isAlive()).thenReturn(true);
            Screen screen = loadingScreen ? mock(LevelLoadingScreen.class) : mock(Screen.class);
            minecraft.screen = screen;
            LevelLoadTracker tracker = new LevelLoadTracker();
            LevelRenderer renderer = mock(LevelRenderer.class);
            BlockPos position = new BlockPos(520, 200, 8);
            when(player.blockPosition()).thenReturn(position);
            when(renderer.isSectionCompiledAndVisible(position)).thenReturn(true);
            tracker.startClientLoad(player, level, renderer);
            try (MockedStatic<Minecraft> singleton = mockStatic(Minecraft.class)) {
                singleton.when(Minecraft::getInstance).thenReturn(minecraft);
                assertFalse(tracker.isLevelReady());
                ClientRenderAccess.finishLevelLoading(minecraft, tracker);
                assertTrue(tracker.isLevelReady());
                tracker.loadingPacketsReceived();
                assertTrue(tracker.isLevelReady());
                if (loadingScreen) {
                    verify(screen).onClose();
                } else {
                    verify(screen, never()).onClose();
                }
            }
        }
    }

    @Test
    public void absentOrUnstartedTrackerLeavesLoadingScreenOpen() throws Exception {
        Minecraft minecraft = mock(Minecraft.class);
        Screen screen = mock(LevelLoadingScreen.class);
        minecraft.screen = screen;
        ClientRenderAccess.finishLevelLoading(minecraft, null);
        LevelLoadTracker tracker = new LevelLoadTracker();
        ClientRenderAccess.finishLevelLoading(minecraft, tracker);
        assertFalse(tracker.isLevelReady());
        verify(screen, never()).onClose();
    }
}
