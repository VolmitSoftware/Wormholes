package art.arcane.wormholes.network.view;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.entity.EntityVisualMetadata;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.transit.AttributeTestSupport;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

final class PacketBlobsTest {
    @BeforeAll
    static void initializeAttributes() {
        AttributeTestSupport.initialize();
    }

    @Test
    void captureIncludesTheEffectiveLivingScaleEvenWithoutVanillaMetadataEntries() {
        LivingEntity entity = mock(LivingEntity.class);
        AttributeInstance scale = mock(AttributeInstance.class);
        when(entity.getAttribute(Attribute.SCALE)).thenReturn(scale);
        when(scale.getValue()).thenReturn(3.5D);
        try (MockedStatic<SpigotConversionUtil> conversion = mockStatic(SpigotConversionUtil.class)) {
            conversion.when(() -> SpigotConversionUtil.getEntityMetadata(entity)).thenReturn(List.of());
            EntityVisualMetadata captured = EntityVisualMetadata.decode(PacketBlobs.captureMetadata(entity));
            assertEquals(GravityFrame.WORLD, captured.gravity());
            assertEquals(3.5D, captured.effectiveScale(), 0.0D);
            assertArrayEquals(new byte[0], captured.data());
        }
    }

    @Test
    void nonLivingEntitiesUseWorldGravityAndUnitScale() {
        Entity entity = mock(Entity.class);
        try (MockedStatic<SpigotConversionUtil> conversion = mockStatic(SpigotConversionUtil.class)) {
            conversion.when(() -> SpigotConversionUtil.getEntityMetadata(entity)).thenReturn(List.of());
            EntityVisualMetadata captured = EntityVisualMetadata.decode(PacketBlobs.captureMetadata(entity));
            assertEquals(GravityFrame.WORLD, captured.gravity());
            assertEquals(1.0D, captured.effectiveScale(), 0.0D);
        }
    }

    @Test
    void metadataPatchingRetainsGravityAndScaleWhenVanillaEntriesBecomeEmpty() {
        GravityFrame sideways = new GravityFrame(AxisPermutation.of(Face.D, Face.E, Face.S));
        byte[] original = new EntityVisualMetadata(sideways, 0.75D, new byte[] {10, 20}).encode();
        byte[] patched = PacketBlobs.replaceMetadata(original, List.of());
        EntityVisualMetadata metadata = EntityVisualMetadata.decode(patched);
        assertEquals(sideways, metadata.gravity());
        assertEquals(0.75D, metadata.effectiveScale(), 0.0D);
        assertArrayEquals(new byte[0], metadata.data());
        assertEquals(List.of(), PacketBlobs.readMetadata(patched));
        assertEquals(List.of(), PacketBlobs.readMetadata(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> PacketBlobs.readMetadata(new byte[EntityVisualMetadata.HEADER_BYTES - 1]));
    }

    @Test
    void scaleChangesInvalidateMetadataReuseWithoutChangingVanillaState() {
        LivingEntity entity = mock(LivingEntity.class);
        AttributeInstance scale = mock(AttributeInstance.class);
        when(entity.getAttribute(Attribute.SCALE)).thenReturn(scale);
        when(scale.getValue()).thenReturn(1.0D, 2.0D, 2.0D);
        try (MockedStatic<WormholesPlatform> platform = mockStatic(WormholesPlatform.class)) {
            platform.when(() -> WormholesPlatform.entityMetadataFingerprint(entity)).thenReturn(101L);
            long original = PacketBlobs.metadataRevision(entity);
            long enlarged = PacketBlobs.metadataRevision(entity);
            assertNotEquals(original, enlarged);
            assertEquals(enlarged, PacketBlobs.metadataRevision(entity));
        }
    }
}
