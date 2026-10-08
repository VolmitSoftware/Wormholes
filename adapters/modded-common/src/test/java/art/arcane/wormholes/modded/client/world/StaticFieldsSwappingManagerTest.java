package art.arcane.wormholes.modded.client.world;

import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.client.multiplayer.ClientLevel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class StaticFieldsSwappingManagerTest extends MinecraftTestBase {
    private float staticField;

    @Test
    public void aPortalRenderSeesTheDestinationContextAndRestoresTheOuterOne() {
        StaticFieldsSwappingManager<Context> manager = manager();
        ClientLevel overworld = mock(ClientLevel.class);
        ClientLevel nether = mock(ClientLevel.class);
        manager.setOuterLevel(overworld);
        staticField = 0.75F;
        manager.pushSwapping(nether);
        assertTrue(manager.isSwapped());
        assertEquals(0.0F, staticField, 0.0F);
        staticField = 0.25F;
        manager.popSwapping();
        assertFalse(manager.isSwapped());
        assertEquals(0.75F, staticField, 0.0F);
        manager.pushSwapping(nether);
        assertEquals(0.25F, staticField, 0.0F);
        manager.popSwapping();
    }

    @Test
    public void crossingHandsTheDestinationContextToTheOuterSlot() {
        StaticFieldsSwappingManager<Context> manager = manager();
        ClientLevel overworld = mock(ClientLevel.class);
        ClientLevel nether = mock(ClientLevel.class);
        manager.setOuterLevel(overworld);
        staticField = 0.75F;
        manager.pushSwapping(nether);
        staticField = 0.25F;
        manager.popSwapping();
        manager.updateOuterLevelAndChangeContext(nether);
        assertEquals(0.25F, staticField, 0.0F);
        manager.updateOuterLevelAndChangeContext(overworld);
        assertEquals(0.75F, staticField, 0.0F);
    }

    @Test(expected = IllegalStateException.class)
    public void theOuterLevelCannotChangeDuringAPortalRender() {
        StaticFieldsSwappingManager<Context> manager = manager();
        ClientLevel overworld = mock(ClientLevel.class);
        manager.setOuterLevel(overworld);
        manager.pushSwapping(mock(ClientLevel.class));
        manager.updateOuterLevelAndChangeContext(overworld);
    }

    private StaticFieldsSwappingManager<Context> manager() {
        return new StaticFieldsSwappingManager<>(context -> staticField = context.value, context -> context.value = staticField, Context::new);
    }

    private static final class Context {
        private float value;
    }
}
