package art.arcane.wormholes.render;

import art.arcane.wormholes.network.view.EntityDeltaCodec;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.PacketBlobs;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.client.ClientViewEntityTransform;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ClientViewSceneCaptureTest {
    @Test
    void nativeAnimalHeadTurnKeepsIndependentBodyYaw() {
        World world = mock(World.class);
        LivingEntity animal = mock(LivingEntity.class);
        when(animal.getUniqueId()).thenReturn(UUID.randomUUID());
        when(animal.getType()).thenReturn(EntityType.COW);
        when(animal.getLocation()).thenReturn(new Location(world, 0, 64, -2, 90, 0));
        when(animal.getEyeLocation()).thenReturn(new Location(world, 0, 65, -2, 90, 0));
        when(animal.getBodyYaw()).thenReturn(25F);
        when(animal.getVelocity()).thenReturn(new Vector());
        when(animal.isValid()).thenReturn(true);
        when(animal.getScoreboardTags()).thenReturn(Set.of());
        ClientViewPortalSource source = mock(ClientViewPortalSource.class);
        ProjectionWorldView view = mock(ProjectionWorldView.class);
        ILocalPortal anchor = mock(ILocalPortal.class);
        when(source.destinationView()).thenReturn(view);
        when(source.destinationWorld()).thenReturn(world);
        when(source.portal()).thenReturn(anchor);
        PortalFrame portalFrame = PortalFrame.canonical(Direction.S);
        ClientViewEntityTransform.Frame frame = new ClientViewEntityTransform.Frame(0, 64, 0, portalFrame,
            0, 64, 0, portalFrame, false, 0, true, 32);
        try (MockedStatic<EntityRenderCaches> nearby = mockStatic(EntityRenderCaches.class);
             MockedStatic<PacketBlobs> blobs = mockStatic(PacketBlobs.class)) {
            nearby.when(() -> EntityRenderCaches.nearbyRemoteEntities(any(), any(), anyDouble())).thenReturn(List.of(animal));
            blobs.when(() -> PacketBlobs.captureMetadata(animal)).thenReturn(EntityVisual.EMPTY);
            blobs.when(() -> PacketBlobs.captureEquipment(animal)).thenReturn(EntityVisual.EMPTY);
            ClientViewSceneCapture capture = new ClientViewSceneCapture();
            EntityVisual first = capture.entities(source, frame, 1, true).getFirst();
            when(animal.getEyeLocation()).thenReturn(new Location(world, 0, 65, -2, 120, 0));
            when(animal.getLocation()).thenReturn(new Location(world, 0, 64, -2, 120, 0));
            EntityVisual turned = capture.entities(source, frame, 2, true).getFirst();
            assertEquals(25, turned.yaw(), 0);
            assertEquals(-Math.sin(Math.toRadians(120)), turned.lookX(), 1.0E-9D);
            int changes = EntityDeltaCodec.computeMask(turned, first);
            assertEquals(EntityVisual.FIELD_LOOK_VEC, changes);
        }
    }
}
