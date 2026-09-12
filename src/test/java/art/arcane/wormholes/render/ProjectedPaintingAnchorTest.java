package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;

final class ProjectedPaintingAnchorTest {
    @Test
    void projectedPaintingsSpawnOnTheirBlockAnchorInsteadOfTheirCentre() {
        List<Entity> nearby = new ArrayList<Entity>();
        World world = RenderTestSupport.world("paintings", nearby);
        Map<String, Object> paintingState = RenderTestSupport.entityState(
            UUID.randomUUID(), world, EntityType.PAINTING, 7.0D, 2.5D, -1.5D, 2.0D);
        paintingState.put("facing", BlockFace.NORTH);
        Painting painting = RenderTestSupport.entity(Painting.class, paintingState);
        nearby.add(painting);

        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        List<WrapperPlayServerSpawnEntity> spawns;
        boolean spoofing = Settings.ENTITY_SPOOFING;
        int maxSpoofed = Settings.MAX_SPOOFED_ENTITIES;
        try {
            Settings.ENTITY_SPOOFING = true;
            Settings.MAX_SPOOFED_ENTITIES = 24;
            project(world);
            spawns = recorder.sentOfType(WrapperPlayServerSpawnEntity.class);
        } finally {
            recorder.uninstall();
            Settings.ENTITY_SPOOFING = spoofing;
            Settings.MAX_SPOOFED_ENTITIES = maxSpoofed;
        }

        assertEquals(1, spawns.size());
        assertEquals(EntityTypes.PAINTING, spawns.get(0).getEntityType());
        assertEquals(1.0D, spawns.get(0).getPosition().getX(), 0.0D);
        assertEquals(2.0D, spawns.get(0).getPosition().getY(), 0.0D);
        assertEquals(7.0D, spawns.get(0).getPosition().getZ(), 0.0D);
    }

    private static void project(World world) {
        PortalFrame remoteFrame = PortalFrame.canonical(Direction.N);
        PortalFrame localFrame = PortalFrame.canonical(Direction.E);
        ILocalPortal localPortal = RenderTestSupport.portal(world, new Vector(0.0D, 0.0D, 0.0D), localFrame);
        ILocalPortal remotePortal = RenderTestSupport.portal(world, new Vector(0.0D, 0.0D, 0.0D), remoteFrame);
        Frustum4D frustum = new Frustum4D(
            new Location(null, 1.5D, 1.5D, 0.0D), new RenderTestSupport.ApertureStructure(), 16.0D, 16.0D);
        EntityRenderPacketChannel channel = new EntityRenderPacketChannel();
        EntityRenderPlayerIdentity identity = new EntityRenderPlayerIdentity(channel);
        EntityRenderSpoofRegistry registry = new EntityRenderSpoofRegistry(channel, identity);
        ProjectedEntityRenderer renderer = new ProjectedEntityRenderer(channel, identity, registry);
        Player observer = ProjectedEntityPacketRecorder.player(true);

        RenderTestSupport.withBukkitServer(() -> renderer.apply(observer, localPortal, remotePortal, frustum, 32.0D,
            localFrame, remoteFrame, 0, new ProjectedEntityOcclusion()));
    }
}
