package art.arcane.wormholes.modded;

import art.arcane.wormholes.access.PortalRole;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.portal.Portal;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel;
import art.arcane.optics.math.Face;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MinecraftSubsystemMenusTest {
    @Test
    public void accessRolesFillRowsUnderTheHeader() {
        assertEquals(1, MinecraftAccessMenu.entryRow(0));
        assertEquals(-4, MinecraftAccessMenu.entryPosition(0));
        assertEquals(4, MinecraftAccessMenu.entryPosition(8));
        assertEquals(2, MinecraftAccessMenu.entryRow(9));
        assertEquals(-4, MinecraftAccessMenu.entryPosition(9));
        assertEquals(1, MinecraftAccessMenu.viewportHeight(0));
        assertEquals(2, MinecraftAccessMenu.viewportHeight(9));
        assertEquals(3, MinecraftAccessMenu.viewportHeight(10));
        assertEquals(6, MinecraftAccessMenu.viewportHeight(500));
    }

    @Test
    public void accessAdditionResolvesLikeBukkit() {
        MinecraftPortal portal = portal();
        UUID guest = UUID.randomUUID();
        assertEquals(MinecraftAccessMenu.AddResolution.NOT_FOUND, MinecraftAccessMenu.resolveAddition(portal, null));
        assertEquals(MinecraftAccessMenu.AddResolution.OWNER, MinecraftAccessMenu.resolveAddition(portal, portal.getOwner()));
        assertEquals(MinecraftAccessMenu.AddResolution.ADD, MinecraftAccessMenu.resolveAddition(portal, guest));
        portal.setRole(guest, PortalRole.USER);
        assertEquals(MinecraftAccessMenu.AddResolution.ALREADY_LISTED, MinecraftAccessMenu.resolveAddition(portal, guest));
    }

    @Test
    public void portalKeepsAccessGroupsAndDirectoryFlag() {
        MinecraftPortal portal = portal();
        assertTrue(portal.isListed());
        assertTrue(portal.getGroups().isEmpty());
        assertFalse(portal.clearGroups());
        assertTrue(portal.addGroup(" group.one "));
        assertFalse(portal.addGroup("group.one"));
        assertFalse(portal.addGroup(" "));
        assertEquals(List.of("group.one"), portal.getGroups());
        assertTrue(portal.clearGroups());
        assertTrue(portal.getGroups().isEmpty());
        portal.setListed(false);
        assertFalse(portal.isListed());
        MinecraftPortal restored = MinecraftPortal.read(portal.write());
        assertFalse(restored.isListed());
    }

    @Test
    public void transitAnswersParseLikeBukkit() {
        assertEquals(2.5D, MinecraftTransitMenu.parseFactor(" 2.5 "), 0D);
        assertNull(MinecraftTransitMenu.parseFactor("10.5"));
        assertNull(MinecraftTransitMenu.parseFactor("NaN"));
        assertNull(MinecraftTransitMenu.parseFactor("fast"));
        assertEquals(Integer.valueOf(-1), MinecraftTransitMenu.parseMaskTicks(" "));
        assertEquals(Integer.valueOf(200), MinecraftTransitMenu.parseMaskTicks("200"));
        assertNull(MinecraftTransitMenu.parseMaskTicks("201"));
        assertNull(MinecraftTransitMenu.parseMaskTicks("-2"));
        assertEquals("", MinecraftTransitMenu.soundOrEmpty(" None "));
        assertEquals("", MinecraftTransitMenu.soundOrEmpty("-"));
        assertEquals("minecraft:block.portal.travel", MinecraftTransitMenu.soundOrEmpty(" Minecraft:Block.Portal.Travel "));
    }

    @Test
    public void rtpBiomesSortByPrettyNameAndSkipIrisKeys() {
        List<RtpPortalEditorModel.BiomeOption> options = MinecraftRtpMenus.biomeOptions(List.of(
            "minecraft:snowy_plains", "minecraft:badlands", "iris:custom", "minecraft:badlands"));
        assertEquals(2, options.size());
        assertEquals("minecraft:badlands", options.get(0).key());
        assertEquals("Badlands", options.get(0).displayName());
        assertEquals("Snowy Plains", options.get(1).displayName());
        assertFalse(options.get(1).irisBiome());
    }

    private static MinecraftPortal portal() {
        ApertureCells geometry = new ApertureCells();
        geometry.setBlocks(List.of(new Vec3d(0, 64, 0), new Vec3d(0, 65, 0)));
        UUID id = UUID.randomUUID();
        return new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(id, geometry.getApertureCenter(), "Access",
            Frame.canonical(Face.N), true), geometry, "minecraft:overworld", Map.of("owner", UUID.randomUUID().toString(), "type", "PORTAL")));
    }
}
