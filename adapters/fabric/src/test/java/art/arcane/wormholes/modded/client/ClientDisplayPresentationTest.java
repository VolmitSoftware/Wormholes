package art.arcane.wormholes.modded.client;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientDisplayPresentationTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void metadataInitializesAllNativeDisplayRenderStatesWithoutLoadedDestinationChunks() {
        ClientLevel level = mock(ClientLevel.class);
        when(level.isClientSide()).thenReturn(true);
        Display[] displays = {
            new Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level),
            new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level),
            new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level)
        };
        ClientLevelScene scene = new ClientLevelScene(level, () -> null);
        for (int i = 0; i < displays.length; i++) {
            Display display = displays[i];
            when(level.getEntity(i)).thenReturn(display);
            display.getEntityData().assignValues(List.of(new SynchedEntityData.DataValue<>(11,
                EntityDataSerializers.VECTOR3, new Vector3f(0, 0.25F, 0))));
            assertNull(display.renderState());
            scene.tick(i, 7, false);
            assertNull(display.renderState());
            scene.tick(i, 7, true);
            assertNotNull(display.renderState());
            assertEquals(1, display.tickCount);
            scene.tick(i, 7, true);
            assertEquals(2, display.tickCount);
        }
        verify(level, never()).getBlockState(any());
        verify(level, never()).getFluidState(any());
    }

}
