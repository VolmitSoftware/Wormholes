package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAttachEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;

final class ProjectedEntityRendererRelationshipsTest {
    @Test
    void localPassSetsPassengersOnTheProjectedVehicle() {
        List<Entity> nearby = new ArrayList<Entity>();
        World world = RenderTestSupport.world("passengers", nearby);
        UUID horseId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        Map<String, Object> horseState = RenderTestSupport.entityState(horseId, world, EntityType.HORSE, 1.5D, 2.0D, 7.0D, 1.6D);
        Map<String, Object> riderState = RenderTestSupport.entityState(riderId, world, EntityType.ZOMBIE, 1.5D, 3.0D, 7.0D, 1.8D);
        LivingEntity horse = RenderTestSupport.entity(LivingEntity.class, horseState);
        LivingEntity rider = RenderTestSupport.entity(LivingEntity.class, riderState);
        horseState.put("passengers", List.of((Entity) rider));
        riderState.put("vehicle", horse);
        nearby.add(horse);
        nearby.add(rider);

        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        List<WrapperPlayServerSpawnEntity> spawns;
        List<WrapperPlayServerSetPassengers> passengers;
        boolean spoofing = Settings.ENTITY_SPOOFING;
        int maxSpoofed = Settings.MAX_SPOOFED_ENTITIES;
        try {
            Settings.ENTITY_SPOOFING = true;
            Settings.MAX_SPOOFED_ENTITIES = 24;
            project(world);
            spawns = recorder.sentOfType(WrapperPlayServerSpawnEntity.class);
            passengers = recorder.sentOfType(WrapperPlayServerSetPassengers.class);
        } finally {
            recorder.uninstall();
            Settings.ENTITY_SPOOFING = spoofing;
            Settings.MAX_SPOOFED_ENTITIES = maxSpoofed;
        }

        assertEquals(2, spawns.size());
        assertEquals(1, passengers.size());
        assertEquals(spawns.get(0).getEntityId(), passengers.get(0).getEntityId());
        assertArrayEquals(new int[] {spawns.get(1).getEntityId()}, passengers.get(0).getPassengers());
    }

    @Test
    void localPassAttachesLeashesBetweenProjectedEntities() {
        List<Entity> nearby = new ArrayList<Entity>();
        World world = RenderTestSupport.world("leash", nearby);
        Map<String, Object> holderState = RenderTestSupport.entityState(
            UUID.randomUUID(), world, EntityType.ZOMBIE, 1.5D, 2.0D, 7.0D, 1.8D);
        Map<String, Object> leashedState = RenderTestSupport.entityState(
            UUID.randomUUID(), world, EntityType.PIG, 1.5D, 2.0D, 8.0D, 0.9D);
        LivingEntity holder = RenderTestSupport.entity(LivingEntity.class, holderState);
        LivingEntity leashed = RenderTestSupport.entity(LivingEntity.class, leashedState);
        leashedState.put("leashed", Boolean.TRUE);
        leashedState.put("leashHolder", holder);
        nearby.add(holder);
        nearby.add(leashed);

        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        List<WrapperPlayServerSpawnEntity> spawns;
        List<WrapperPlayServerAttachEntity> attaches;
        boolean spoofing = Settings.ENTITY_SPOOFING;
        int maxSpoofed = Settings.MAX_SPOOFED_ENTITIES;
        try {
            Settings.ENTITY_SPOOFING = true;
            Settings.MAX_SPOOFED_ENTITIES = 24;
            project(world);
            spawns = recorder.sentOfType(WrapperPlayServerSpawnEntity.class);
            attaches = recorder.sentOfType(WrapperPlayServerAttachEntity.class);
        } finally {
            recorder.uninstall();
            Settings.ENTITY_SPOOFING = spoofing;
            Settings.MAX_SPOOFED_ENTITIES = maxSpoofed;
        }

        assertEquals(2, spawns.size());
        assertEquals(1, attaches.size());
        assertEquals(spawns.get(1).getEntityId(), attaches.get(0).getAttachedId());
        assertEquals(spawns.get(0).getEntityId(), attaches.get(0).getHoldingId());
        assertTrue(attaches.get(0).isLeash());
    }

    @Test
    void relationshipsComeFromTheLiveEntity() {
        World world = RenderTestSupport.world("collect", List.of());
        UUID horseId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        Map<String, Object> horseState = RenderTestSupport.entityState(horseId, world, EntityType.HORSE, 0.0D, 0.0D, 0.0D, 1.6D);
        Map<String, Object> riderState = RenderTestSupport.entityState(riderId, world, EntityType.ZOMBIE, 0.0D, 0.0D, 0.0D, 1.8D);
        LivingEntity horse = RenderTestSupport.entity(LivingEntity.class, horseState);
        LivingEntity rider = RenderTestSupport.entity(LivingEntity.class, riderState);
        horseState.put("passengers", List.of((Entity) rider));
        riderState.put("vehicle", horse);
        riderState.put("leashed", Boolean.TRUE);
        riderState.put("leashHolder", horse);

        EntityRelationship vehicle = EntityRelationship.of(horse);
        EntityRelationship passenger = EntityRelationship.of(rider);

        assertEquals(horseId, vehicle.entityId());
        assertNull(vehicle.vehicleId());
        assertEquals(List.of(riderId), vehicle.passengerIds());
        assertNull(vehicle.leashHolderId());
        assertEquals(horseId, passenger.vehicleId());
        assertEquals(List.of(), passenger.passengerIds());
        assertEquals(horseId, passenger.leashHolderId());
    }

    private static void project(World world) {
        PortalFrame frame = PortalFrame.canonical(Direction.N);
        ILocalPortal localPortal = RenderTestSupport.portal(world, new Vector(0.0D, 0.0D, 0.0D), frame);
        ILocalPortal remotePortal = RenderTestSupport.portal(world, new Vector(0.0D, 0.0D, 0.0D), frame);
        Frustum4D frustum = new Frustum4D(
            new Location(null, 1.5D, 1.5D, 0.0D), new RenderTestSupport.ApertureStructure(), 16.0D, 16.0D);
        EntityRenderPacketChannel channel = new EntityRenderPacketChannel();
        EntityRenderPlayerIdentity identity = new EntityRenderPlayerIdentity(channel);
        EntityRenderSpoofRegistry registry = new EntityRenderSpoofRegistry(channel, identity);
        ProjectedEntityRenderer renderer = new ProjectedEntityRenderer(channel, identity, registry);
        Player observer = ProjectedEntityPacketRecorder.player(true);

        RenderTestSupport.withBukkitServer(() -> renderer.apply(observer, localPortal, remotePortal, frustum, 32.0D,
            frame, frame, 0, new ProjectedEntityOcclusion()));
    }
}
