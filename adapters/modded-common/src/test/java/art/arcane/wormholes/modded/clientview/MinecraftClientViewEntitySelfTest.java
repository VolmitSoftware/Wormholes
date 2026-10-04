package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.view.EntityVisual;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftClientViewEntitySelfTest {
    @Test
    public void sessionBindingUsesTheActualNativeObserverNamespaceOnly() {
        MinecraftClientViewScene scene = new MinecraftClientViewScene(mock(WormholesModRuntime.class),
            mock(MinecraftClientViewPortalAccess.class));
        MinecraftClientViewPeer observer = mock(MinecraftClientViewPeer.class);
        UUID source = UUID.randomUUID();
        when(observer.id()).thenReturn(source);
        UUID projected = scene.projectedId(source);
        assertNotEquals(source, projected);
        EntityVisual self = EntityVisual.full(projected, "minecraft:player", 0, 64, 0, 1.8, 0, 0, 1, 0, 0,
            0, 0, 0, true, "", "", "", null, null, EntityVisual.EMPTY, EntityVisual.EMPTY, 0);
        assertTrue(scene.isObserver(observer, self));
        when(observer.id()).thenReturn(UUID.randomUUID());
        assertFalse(scene.isObserver(observer, self));
    }
}
