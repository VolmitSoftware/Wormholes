package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.entity.EntitySnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftClientViewObserverTest extends MinecraftTestBase {
    @Test
    public void observerMatchesOnlyItsOwnProjectedId() {
        MinecraftClientViewScene scene = new MinecraftClientViewScene(mock(WormholesModRuntime.class),
            mock(MinecraftClientViewPortalAccess.class));
        MinecraftClientViewPeer observer = mock(MinecraftClientViewPeer.class);
        UUID source = UUID.randomUUID();
        when(observer.id()).thenReturn(source);
        UUID projected = scene.projectedId(source);
        assertNotEquals(source, projected);
        EntitySnapshot self = EntitySnapshot.full(projected, "minecraft:player", 0, 64, 0, 1.8, 0, 0, 1, 0, 0,
            0, 0, 0, true, "", "", "", null, null, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, 0);
        assertTrue(scene.isObserver(observer, self));
        when(observer.id()).thenReturn(UUID.randomUUID());
        assertFalse(scene.isObserver(observer, self));
    }

    @Test
    public void recursiveLinkedObserverUsesTheComposedDestinationEye() {
        MinecraftClientViewPortalAccess access = mock(MinecraftClientViewPortalAccess.class);
        MinecraftClientViewScene scene = new MinecraftClientViewScene(mock(WormholesModRuntime.class), access);
        MinecraftClientViewPeer observer = mock(MinecraftClientViewPeer.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel world = mock(ServerLevel.class);
        MinecraftPortal portal = mock(MinecraftPortal.class);
        UUID portalId = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        Vec3d eye = new Vec3d(0.0D, 65.6D, 0.0D);
        when(observer.meshDepth()).thenReturn(64);
        when(observer.player()).thenReturn(player);
        when(player.level()).thenReturn(world);
        when(player.getEyePosition()).thenReturn(new Vec3(eye.x(), eye.y(), eye.z()));
        when(access.portal(observer, child)).thenReturn(portal);
        OpticTransform transform = OpticTransform.translation(0.0D, 0.0D, -4.0D);
        Vec3d parentEye = transform.inverse().point(eye);
        when(observer.nestedContext(child)).thenReturn(new MinecraftClientViewPeer.NestedContext(portalId, parentEye,
            transform.inverse().point(parentEye), world, transform));
        assertTrue(scene.observerVisible(observer, child));
        when(observer.nestedContext(portalId)).thenReturn(new MinecraftClientViewPeer.NestedContext(portalId, eye,
            new Vec3d(0.0D, 65.6D, 8.0D), world, OpticTransform.IDENTITY));
        assertFalse(scene.observerVisible(observer, portalId));
        when(observer.nestedContext(child)).thenReturn(new MinecraftClientViewPeer.NestedContext(portalId, parentEye,
            transform.point(parentEye), world, transform.inverse()));
        assertFalse(scene.observerVisible(observer, child));
        when(observer.nestedContext(child)).thenReturn(new MinecraftClientViewPeer.NestedContext(portalId, eye,
            new Vec3d(0.0D, 65.6D, 1.0D), world, OpticTransform.IDENTITY));
        assertFalse(scene.observerVisible(observer, child));
        when(observer.nestedContext(child)).thenReturn(new MinecraftClientViewPeer.NestedContext(portalId, eye,
            new Vec3d(0.0D, 65.6D, 1.001D), world, OpticTransform.IDENTITY));
        assertTrue(scene.observerVisible(observer, child));
        when(portal.isMirrorMode()).thenReturn(true);
        assertFalse(scene.observerVisible(observer, child));
        when(portal.isMirrorMode()).thenReturn(false);
        when(player.level()).thenReturn(mock(ServerLevel.class));
        assertFalse(scene.observerVisible(observer, child));
        when(player.level()).thenReturn(world);
        when(observer.meshDepth()).thenReturn(0);
        assertFalse(scene.observerVisible(observer, child));
    }

    @Test
    public void sidewaysEyeOffsetsDoNotExposeTheObserverAtItsOwnCamera() {
        MinecraftClientViewPortalAccess access = mock(MinecraftClientViewPortalAccess.class);
        MinecraftClientViewScene scene = new MinecraftClientViewScene(mock(WormholesModRuntime.class), access);
        MinecraftClientViewPeer observer = mock(MinecraftClientViewPeer.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel world = mock(ServerLevel.class);
        MinecraftPortal portal = mock(MinecraftPortal.class);
        UUID portalId = UUID.randomUUID();
        UUID context = UUID.randomUUID();
        Vec3d eye = new Vec3d(1.62D, 64.0D, 0.0D);
        when(observer.meshDepth()).thenReturn(64);
        when(observer.player()).thenReturn(player);
        when(player.level()).thenReturn(world);
        when(player.getX()).thenReturn(0.0D);
        when(player.getEyeY()).thenReturn(64.0D);
        when(player.getEyePosition()).thenReturn(new Vec3(eye.x(), eye.y(), eye.z()));
        when(access.portal(observer, context)).thenReturn(portal);
        when(observer.nestedContext(context)).thenReturn(new MinecraftClientViewPeer.NestedContext(portalId, eye, eye, world, OpticTransform.IDENTITY));
        assertFalse(scene.observerVisible(observer, context));
    }
}
