package art.arcane.wormholes.modded;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.Portal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftPortalLookLabelsTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void lookingAtRequiresRangeFromTheCentreAndARayThroughAnApertureCell() {
        PortalGeometry geometry = plane(0, 1, 64, 65, 4);
        Vec3 position = new Vec3(1.0D, 64.0D, 0.0D);
        Vec3 eye = new Vec3(1.0D, 65.62D, 0.0D);

        assertTrue(MinecraftPortalLookLabels.isLookingAt(geometry, position, eye, new Vec3(0.0D, 0.0D, 1.0D)));
        assertFalse(MinecraftPortalLookLabels.isLookingAt(geometry, position, eye, new Vec3(0.0D, 0.0D, -1.0D)));
        assertFalse(MinecraftPortalLookLabels.isLookingAt(geometry, position, eye, new Vec3(1.0D, 0.0D, 0.0D)));
        assertFalse(MinecraftPortalLookLabels.isLookingAt(geometry, new Vec3(1.0D, 64.0D, -4.0D),
            new Vec3(1.0D, 65.62D, -4.0D), new Vec3(0.0D, 0.0D, 1.0D)));
    }

    @Test
    public void lookingAtMissesHolesInsideTheApertureBounds() {
        PortalGeometry geometry = new PortalGeometry();
        geometry.setBlocks(List.of(new GeometryVector(0, 64, 4), new GeometryVector(2, 64, 4)));

        assertTrue(MinecraftPortalLookLabels.isLookingAt(geometry, new Vec3(0.5D, 64.0D, 0.0D),
            new Vec3(0.5D, 64.5D, 0.0D), new Vec3(0.0D, 0.0D, 1.0D)));
        assertFalse(MinecraftPortalLookLabels.isLookingAt(geometry, new Vec3(1.5D, 64.0D, 0.0D),
            new Vec3(1.5D, 64.5D, 0.0D), new Vec3(0.0D, 0.0D, 1.0D)));
    }

    @Test
    public void lookingAtStopsSixteenBlocksFromTheEye() {
        PortalGeometry geometry = plane(-20, 20, 64, 64, 4);
        Vec3 position = new Vec3(0.5D, 64.0D, 0.0D);
        Vec3 eye = new Vec3(0.5D, 64.5D, 0.0D);
        Vec3 near = new Vec3(10.0D, 0.0D, 4.0D).normalize();
        Vec3 far = new Vec3(19.0D, 0.0D, 4.5D).normalize();

        assertTrue(MinecraftPortalLookLabels.isLookingAt(geometry, position, eye, near));
        assertFalse(MinecraftPortalLookLabels.isLookingAt(geometry, position, eye, far));
    }

    @Test
    public void publicLabelsAdmitNonToolViewers() {
        MinecraftPortal hidden = portal(plane(0, 1, 64, 65, 4), false);
        MinecraftPortal labelled = portal(plane(0, 1, 64, 65, 8), true);

        assertFalse(MinecraftPortalLookLabels.hasPublicLookLabel(List.of(hidden)));
        assertTrue(MinecraftPortalLookLabels.hasPublicLookLabel(List.of(hidden, labelled)));
    }

    @Test
    public void candidatesIndexPortalsByWorldAndPaddedArea() {
        MinecraftPortal close = portal(plane(0, 1, 64, 65, 4), false);
        MinecraftPortal distant = portal(plane(1000, 1001, 64, 65, 4), false);
        MinecraftPortal nether = new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(UUID.randomUUID(),
            new GeometryVector(0, 64, 4), "Nether", PortalFrame.canonical(Direction.N), true), plane(0, 1, 64, 65, 4),
            "minecraft:the_nether", Map.of("type", "PORTAL")));
        MinecraftPortalCandidates candidates = MinecraftPortalCandidates.capture(List.of(close, distant, nether), 32.0D);

        assertEquals(List.of(close), candidates.near(Level.OVERWORLD, 1.0D, 0.0D));
        assertEquals(List.of(close), candidates.near(Level.OVERWORLD, -30.0D, -30.0D));
        assertEquals(List.of(distant), candidates.near(Level.OVERWORLD, 1000.0D, 0.0D));
        assertEquals(List.of(), candidates.near(Level.OVERWORLD, 500.0D, 0.0D));
        assertEquals(List.of(nether), candidates.near(Level.NETHER, 1.0D, 0.0D));
        assertEquals(List.of(), candidates.near(Level.END, 1.0D, 0.0D));
    }

    @Test
    public void toolPreviewOutlinesOnlyTheExactApertureBoundary() {
        MinecraftPortalToolPreview.Geometry rectangle = MinecraftPortalToolPreview.buildGeometry(
            plane(0, 1, 64, 65, 4).getBlockPositions(), Axis.Z);
        MinecraftPortalToolPreview.Geometry lShape = MinecraftPortalToolPreview.buildGeometry(List.of(
            new GeometryVector(0, 64, 4), new GeometryVector(1, 64, 4), new GeometryVector(0, 65, 4)), Axis.Z);

        assertEquals(4, rectangle.cells().size());
        assertEquals(8 * 4, rectangle.outlinePoints().size());
        assertTrue(rectangle.outlinePoints().stream().allMatch(point -> point.z() == 4.5D));
        assertEquals(0.0D, rectangle.distanceSquared(1.0D, 65.0D, 4.5D), 0.0D);
        assertEquals(4.0D, rectangle.distanceSquared(1.0D, 65.0D, 7.0D), 1.0E-9D);
        assertEquals(8 * 4, lShape.outlinePoints().size());
        assertTrue(lShape.outlinePoints().stream().noneMatch(point -> point.x() > 1.0D && point.y() > 65.0D));
    }

    @Test
    public void shortTitlesFadeInFreshLooksAndHoldContinuousOnes() {
        AtomicLong ticks = new AtomicLong(100L);
        MinecraftShortTitles titles = new MinecraftShortTitles(ticks::get);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        player.connection = mock(ServerGamePacketListenerImpl.class);
        UUID portal = UUID.randomUUID();
        List<Integer> fades = new ArrayList<>();

        titles.send(player, portal, "§6§lGate");
        fades.add(fade(player, 1));
        ticks.addAndGet(3L);
        titles.send(player, portal, "§6§lGate");
        ticks.addAndGet(3L);
        titles.send(player, portal, "§6§lGate");
        fades.add(fade(player, 2));
        titles.send(player, UUID.randomUUID(), "§6§lOther");
        fades.add(fade(player, 3));
        ticks.addAndGet(9L);
        titles.send(player, portal, "§6§lGate");
        fades.add(fade(player, 4));

        assertEquals(List.of(MinecraftShortTitles.FADE_IN_TICKS, 0, MinecraftShortTitles.FADE_IN_TICKS,
            MinecraftShortTitles.FADE_IN_TICKS), fades);
        ArgumentCaptor<Packet<?>> packets = packets(player, 12);
        ClientboundSetSubtitleTextPacket subtitle = (ClientboundSetSubtitleTextPacket) packets.getAllValues().get(1);
        assertEquals(MinecraftLegacyText.component("§6§lGate"), subtitle.text());
        ClientboundSetTitleTextPacket title = (ClientboundSetTitleTextPacket) packets.getAllValues().get(2);
        assertEquals(Component.empty(), title.text());
        ClientboundSetTitlesAnimationPacket animation = (ClientboundSetTitlesAnimationPacket) packets.getAllValues().get(0);
        assertEquals(MinecraftShortTitles.STAY_TICKS, animation.getStay());
        assertEquals(MinecraftShortTitles.FADE_OUT_TICKS, animation.getFadeOut());
    }

    private static int fade(ServerPlayer player, int sends) {
        ArgumentCaptor<Packet<?>> packets = packets(player, sends * 3);
        ClientboundSetTitlesAnimationPacket animation = (ClientboundSetTitlesAnimationPacket) packets.getAllValues().get((sends - 1) * 3);
        return animation.getFadeIn();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Packet<?>> packets(ServerPlayer player, int expected) {
        ArgumentCaptor<Packet<?>> packets = ArgumentCaptor.forClass(Packet.class);
        verify(player.connection, atLeast(expected)).send(packets.capture());
        assertEquals(expected, packets.getAllValues().size());
        return packets;
    }

    private static PortalGeometry plane(int minX, int maxX, int minY, int maxY, int z) {
        List<GeometryVector> cells = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                cells.add(new GeometryVector(x, y, z));
            }
        }
        PortalGeometry geometry = new PortalGeometry();
        geometry.setBlocks(cells);
        return geometry;
    }

    private static MinecraftPortal portal(PortalGeometry geometry, boolean publicLookLabel) {
        UUID id = UUID.randomUUID();
        MinecraftPortal portal = new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(id, geometry.getApertureCenter(),
            "Look", PortalFrame.canonical(Direction.N), true), geometry, "minecraft:overworld", Map.of("owner", id.toString(), "type", "PORTAL")));
        portal.setPublicLookLabel(publicLookLabel);
        return portal;
    }
}
