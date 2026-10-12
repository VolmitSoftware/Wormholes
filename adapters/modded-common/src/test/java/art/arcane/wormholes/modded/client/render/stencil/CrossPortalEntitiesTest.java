package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.GravityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class CrossPortalEntitiesTest extends MinecraftTestBase {
    @Test
    public void scaledQuarterTurnCopiesComposeExistingGravityAndKeepLocalAngles() {
        AxisPermutation quarter = AxisPermutation.of(Face.D, Face.E, Face.S);
        GravityFrame initial = new GravityFrame(AxisPermutation.of(Face.E, Face.D, Face.N));
        LivingEntityRenderState copy = mock(LivingEntityRenderState.class, withSettings().useConstructor().extraInterfaces(GravityRenderState.class));
        GravityRenderState gravity = (GravityRenderState) copy;
        AtomicReference<GravityFrame> basis = new AtomicReference<>(initial);
        when(gravity.wormholes$gravity()).thenAnswer(invocation -> basis.get());
        doAnswer(invocation -> {
            basis.set(invocation.getArgument(0, GravityFrame.class));
            return null;
        }).when(gravity).wormholes$gravity(any(GravityFrame.class));
        copy.bodyRot = 37.0F;
        copy.yRot = -12.0F;
        copy.xRot = 28.0F;
        copy.scale = 1.0F;
        copy.boundingBoxWidth = 0.6F;
        copy.boundingBoxHeight = 1.8F;
        copy.eyeHeight = 1.62F;
        copy.nameTagAttachment = new Vec3(0.0D, 2.0D, 0.0D);
        copy.passengerOffset = new Vec3(0.0D, 1.0D, 0.0D);
        Similarity toward = Similarity.of(OpticTransform.of(quarter, 20.0D, 30.0D, 40.0D), 0.5D);
        assertTrue(CrossPortalEntities.transform(copy, toward));
        assertEquals(initial.transformed(quarter), basis.get());
        assertEquals(37.0F, copy.bodyRot, 0.0F);
        assertEquals(-12.0F, copy.yRot, 0.0F);
        assertEquals(28.0F, copy.xRot, 0.0F);
        assertEquals(0.5F, copy.scale, 0.0F);
        assertEquals(0.3F, copy.boundingBoxWidth, 0.0F);
        assertEquals(0.9F, copy.boundingBoxHeight, 0.0F);
        assertEquals(0.81F, copy.eyeHeight, 0.0F);
        assertVector(new Vec3(1.0D, 0.0D, 0.0D), copy.nameTagAttachment);
        assertVector(new Vec3(0.5D, 0.0D, 0.0D), copy.passengerOffset);
        assertTrue(CrossPortalEntities.transform(copy, toward.inverse()));
        assertEquals(initial, basis.get());
        assertEquals(1.0F, copy.scale, 0.0F);
        assertVector(new Vec3(0.0D, 2.0D, 0.0D), copy.nameTagAttachment);
    }

    private static void assertVector(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 0.0D);
        assertEquals(expected.y, actual.y, 0.0D);
        assertEquals(expected.z, actual.z, 0.0D);
    }

    @Test
    public void reflectionsCannotBecomeGravityFramesAndUnscaledOrdinaryEntitiesStillCopy() {
        EntityRenderState copy = mock(EntityRenderState.class, withSettings().useConstructor());
        Similarity reflection = Similarity.of(OpticTransform.of(AxisPermutation.of(Face.W, Face.U, Face.S), 0.0D, 0.0D, 0.0D), 1.0D);
        assertFalse(CrossPortalEntities.transform(copy, reflection));
        assertFalse(CrossPortalEntities.transform(copy, Similarity.of(OpticTransform.IDENTITY, 2.0D)));
        assertFalse(CrossPortalEntities.transform(copy, Similarity.of(OpticTransform.of(AxisPermutation.of(Face.D, Face.E, Face.S),
            0.0D, 0.0D, 0.0D), 1.0D)));
        assertTrue(CrossPortalEntities.transform(copy, Similarity.IDENTITY));
    }

    @Test
    public void scaledAndUpChangingCopiesDiscardSourceFloorShadows() {
        Similarity[] transforms = {
            Similarity.of(OpticTransform.IDENTITY, 0.5D),
            Similarity.of(OpticTransform.of(AxisPermutation.of(Face.D, Face.E, Face.S), 0.0D, 0.0D, 0.0D), 1.0D)
        };
        for (Similarity transform : transforms) {
            LivingEntityRenderState copy = mock(LivingEntityRenderState.class,
                withSettings().useConstructor().extraInterfaces(GravityRenderState.class));
            when(((GravityRenderState) copy).wormholes$gravity()).thenReturn(GravityFrame.WORLD);
            copy.shadowRadius = 0.5F;
            copy.shadowPieces.add(mock(EntityRenderState.ShadowPiece.class));

            assertTrue(CrossPortalEntities.transform(copy, transform));
            assertEquals(0.0F, copy.shadowRadius, 0.0F);
            assertTrue(copy.shadowPieces.isEmpty());
        }
    }

    @Test
    public void rigidWorldUpCopiesPreserveExistingShadows() {
        EntityRenderState copy = mock(EntityRenderState.class, withSettings().useConstructor());
        EntityRenderState.ShadowPiece shadow = mock(EntityRenderState.ShadowPiece.class);
        copy.shadowRadius = 0.5F;
        copy.shadowPieces.add(shadow);
        Similarity transform = Similarity.of(OpticTransform.of(AxisPermutation.of(Face.N, Face.U, Face.E),
            20.0D, 30.0D, 40.0D), 1.0D);

        assertTrue(CrossPortalEntities.transform(copy, transform));
        assertEquals(0.5F, copy.shadowRadius, 0.0F);
        assertEquals(1, copy.shadowPieces.size());
        assertSame(shadow, copy.shadowPieces.getFirst());
    }
}
