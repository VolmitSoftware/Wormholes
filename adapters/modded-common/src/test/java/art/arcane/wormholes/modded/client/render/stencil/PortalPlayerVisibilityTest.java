package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.math.Vec3d;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PortalPlayerVisibilityTest {
    @Test
    public void linkedViewsKeepTheAvatarVisibleWithoutDependingOnPerspective() {
        for (PortalView.Kind kind : new PortalView.Kind[]{PortalView.Kind.ARM, PortalView.Kind.RETURN, PortalView.Kind.CROSSING}) {
            assertTrue(PortalWorldRenderer.selfVisible(kind, false));
            assertTrue(PortalWorldRenderer.selfVisible(kind, true));
        }
    }

    @Test
    public void mirrorsRespectTheirSelfReflectionSetting() {
        assertTrue(PortalWorldRenderer.selfVisible(PortalView.Kind.MIRROR, true));
        assertFalse(PortalWorldRenderer.selfVisible(PortalView.Kind.MIRROR, false));
    }

    @Test
    public void recursiveCamerasShowRemoteBodiesButHideTheBodyAtTheirEye() {
        Vec3 eye = new Vec3(48.5D, 71.62D, 30.5D);
        assertFalse(PortalWorldRenderer.detached(eye, eye));
        assertFalse(PortalWorldRenderer.detached(eye, eye.add(0.0D, 0.0D, 1.0D)));
        assertTrue(PortalWorldRenderer.detached(eye, eye.add(0.0D, 0.0D, 1.01D)));
        assertTrue(PortalWorldRenderer.detached(eye, eye.add(0.0D, 0.0D, 24.0D)));
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
