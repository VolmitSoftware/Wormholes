package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.math.Vec3d;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PortalPlayerVisibilityTest {
    @Test
    public void armedAndReturnViewsKeepTheThirdPersonAvatarVisible() {
        for (PortalView.Kind kind : new PortalView.Kind[]{PortalView.Kind.ARM, PortalView.Kind.RETURN}) {
            assertTrue(PortalWorldRenderer.selfVisible(kind, false, false));
            assertFalse(PortalWorldRenderer.selfVisible(kind, true, true));
        }
    }

    @Test
    public void mirrorsRespectTheirSelfReflectionSettingInBothPerspectives() {
        for (boolean firstPerson : new boolean[]{true, false}) {
            assertTrue(PortalWorldRenderer.selfVisible(PortalView.Kind.MIRROR, firstPerson, true));
            assertFalse(PortalWorldRenderer.selfVisible(PortalView.Kind.MIRROR, firstPerson, false));
            assertTrue(PortalWorldRenderer.selfVisible(PortalView.Kind.CROSSING, firstPerson, false));
        }
    }

    @Test
    public void mappedLocalAvatarIsKeptDistinctFromItsOriginalAndOtherPlayers() {
        AvatarRenderState local = new AvatarRenderState();
        local.id = 7;
        local.x = 4.0D;
        local.y = 65.0D;
        local.z = 9.0D;
        List<EntityRenderState> states = List.of(local);
        assertTrue(CrossPortalEntities.playerAlreadyVisible(states, 7, new Vec3d(4.0D, 65.0D, 9.0D)));
        assertFalse(CrossPortalEntities.playerAlreadyVisible(states, 7, new Vec3d(104.0D, 65.0D, 9.0D)));
        assertFalse(CrossPortalEntities.playerAlreadyVisible(states, 8, new Vec3d(4.0D, 65.0D, 9.0D)));
        assertFalse(CrossPortalEntities.playerAlreadyVisible(List.of(), 7, new Vec3d(4.0D, 65.0D, 9.0D)));
    }
}
