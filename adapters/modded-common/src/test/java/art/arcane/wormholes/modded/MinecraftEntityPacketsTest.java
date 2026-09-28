package art.arcane.wormholes.modded;

import art.arcane.wormholes.network.view.ProjectedMapData;
import art.arcane.wormholes.network.view.RemoteViewCache.RemoteProfile;
import art.arcane.wormholes.render.EntityRenderSpoofedEntity;
import art.arcane.wormholes.render.ProjectedPlayerNames;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MinecraftEntityPacketsTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void projectedSwingUsesTheNativeAnimationActionAndFakeId() {
        ClientboundAnimatePacket packet = MinecraftEntityPackets.animation(1_900_000_001, ClientboundAnimatePacket.SWING_OFF_HAND);
        assertEquals(1_900_000_001, packet.getId());
        assertEquals(3, packet.getAction());
    }

    @Test
    public void playerInfoPreservesSignedSkinAndHidesTabEntry() {
        EntityRenderSpoofedEntity state = EntityRenderSpoofedEntity.create(true, false, true);
        state.setPlayerIdentity(ProjectedPlayerNames.syntheticProfileName(state.fakeUuid), "PortalTester");
        ClientboundPlayerInfoUpdatePacket packet = MinecraftEntityPackets.playerInfo(RegistryAccess.EMPTY, state,
            new RemoteProfile("PortalTester", "texture-value", "texture-signature"));
        ClientboundPlayerInfoUpdatePacket.Entry entry = packet.entries().getFirst();
        assertEquals(state.fakeUuid, entry.profileId());
        assertEquals(state.playerProfileName, entry.profile().name());
        assertEquals("texture-signature", entry.profile().properties().get("textures").iterator().next().signature());
        assertFalse(entry.listed());
        assertTrue(entry.showHat());
    }

    @Test
    public void relationshipsAndAbsoluteMotionUseFakeEntityIds() {
        ClientboundSetPassengersPacket riders = MinecraftEntityPackets.passengersPacket(1_900_000_001, new int[] {1_900_000_002});
        assertEquals(1_900_000_001, riders.getVehicle());
        assertArrayEquals(new int[] {1_900_000_002}, riders.getPassengers());
        ClientboundSetEntityLinkPacket leash = MinecraftEntityPackets.leashPacket(1_900_000_002, -1);
        assertEquals(1_900_000_002, leash.getSourceId());
        assertEquals(-1, leash.getDestId());
        Vec3 position = new Vec3(-120.25D, 65.75D, 340.5D);
        ClientboundTeleportEntityPacket teleport = MinecraftEntityPackets.teleport(1_900_000_001, position, 37.0F, -22.0F, true);
        assertEquals(position, teleport.change().position());
        assertTrue(teleport.relatives().isEmpty());
        assertTrue(teleport.onGround());
        assertEquals(-6144, MinecraftEntityPackets.delta(-1.5D));
    }

    @Test
    public void mapAndPlayerMetadataPreserveSharedProjectionSemantics() {
        byte[] pixels = new byte[ProjectedMapData.PIXEL_COUNT];
        pixels[0] = 24;
        ClientboundMapItemDataPacket packet = MinecraftEntityVisualHost.mapPacket(new ProjectedMapData(17, (byte) 2, true, true, pixels), -1_900_000_001);
        assertEquals(-1_900_000_001, packet.mapId().id());
        assertEquals((byte) 2, packet.scale());
        assertTrue(packet.locked());
        List<SynchedEntityData.DataValue<?>> metadata = MinecraftEntityMetadata.ENTITIES.upsideDownPlayer(
            List.of(new SynchedEntityData.DataValue<>(16, EntityDataSerializers.BYTE, (byte) 0x20)));
        assertEquals((byte) 0x21, metadata.getFirst().value());
        List<SynchedEntityData.DataValue<?>> label = MinecraftEntityVisualHost.labelMetadata("PortalTester");
        assertEquals(3, label.getFirst().value());
        assertEquals(10, label.getFirst().id());
    }
}
