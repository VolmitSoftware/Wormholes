package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.portal.ApertureKind;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class ClientPortalRendererScaleMatrixTest extends MinecraftTestBase {
    private static final ApertureDescriptor WALL = new ApertureDescriptor(10, 64, -4, Face.N.ordinal(), true, 0, false, 3, 3,
        new long[] {511L}, ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 11, List.of());

    @Test
    public void unitScaleLeavesTheContentSpaceAlone() {
        Matrix4d toRoot = new Matrix4d().translate(3.0D, -2.0D, 7.0D);

        assertEquals(toRoot, ClientPortalRenderer.contentSpace(toRoot, WALL, 1.0F));
    }

    @Test
    public void scaledContentShrinksAboutTheApertureCentre() {
        Vec3d center = ClientPortalRenderer.scaleCenter(WALL);
        Matrix4d content = ClientPortalRenderer.contentSpace(new Matrix4d(), WALL, 1.0F / 3.0F);

        assertEquals(new Vec3d(11.5D, 65.5D, -3.5D), center);
        Vector3d fixed = content.transformPosition(new Vector3d(center.x(), center.y(), center.z()));
        assertEquals(center.x(), fixed.x, 1.0E-6D);
        assertEquals(center.y(), fixed.y, 1.0E-6D);
        assertEquals(center.z(), fixed.z, 1.0E-6D);
        Vector3d far = content.transformPosition(new Vector3d(center.x() + 9.0D, center.y() + 3.0D, center.z() - 6.0D));
        assertEquals(center.x() + 3.0D, far.x, 1.0E-5D);
        assertEquals(center.y() + 1.0D, far.y, 1.0E-5D);
        assertEquals(center.z() - 2.0D, far.z, 1.0E-5D);
    }

    @Test
    public void similarityMatricesMatchTheRigidMatrixAtUnitScaleAndScaleOtherwise() {
        OpticTransform rigid = OpticTransform.of(AxisPermutation.of(Face.S, Face.U, Face.W), 100.0D, 20.0D, 200.0D);
        Matrix4d unit = PortalProjection.matrix(Similarity.of(rigid, 1.0D));
        Matrix4d expected = PortalProjection.destinationToSource(rigid);

        assertEquals(expected.m00(), unit.m00(), 0.0D);
        assertEquals(expected.m02(), unit.m02(), 0.0D);
        assertEquals(expected.m20(), unit.m20(), 0.0D);
        assertEquals(expected.m30(), unit.m30(), 0.0D);
        assertEquals(expected.m32(), unit.m32(), 0.0D);
        Similarity scaled = Similarity.of(rigid, 3.0D);
        Vector3d mapped = PortalProjection.matrix(scaled).transformPosition(new Vector3d(1.0D, 2.0D, 3.0D));
        Vec3d direct = scaled.point(new Vec3d(1.0D, 2.0D, 3.0D));
        assertEquals(direct.x(), mapped.x, 1.0E-9D);
        assertEquals(direct.y(), mapped.y, 1.0E-9D);
        assertEquals(direct.z(), mapped.z, 1.0E-9D);
    }
}
